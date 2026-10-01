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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImapPopDocumentLoaderTest {

    private EmailConnectionFactory mockFactory;
    private ImapPopDocumentLoader loader;

    @TempDir
    Path tempDir;

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

    @Test
    void attachmentWithFilenameButInlineDispositionIsNotTreatedAsBody() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject("No Attachment Disposition");
        msg.setFrom(new InternetAddress("from@test.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("to@test.com"));

        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setText("This is the real body.", "UTF-8");
        multipart.addBodyPart(bodyPart);

        // A2's rule is "Content-Disposition attachment OR a filename at all" — this part is
        // explicitly "inline" (not "attachment") but still carries a filename, so it must still
        // be classified as an attachment, never mistaken for the body.
        MimeBodyPart namedInlinePart = new MimeBodyPart();
        namedInlinePart.setText("Attachment content, not the body.", "UTF-8");
        namedInlinePart.setHeader("Content-Disposition", "inline");
        namedInlinePart.setFileName("data.txt");
        multipart.addBodyPart(namedInlinePart);

        msg.setContent(multipart);
        msg.saveChanges();
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        assertEquals(2, docs.size());
        Document emailDoc = docs.stream()
                .filter(d -> !Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        assertTrue(emailDoc.getText().contains("This is the real body."));
        assertFalse(emailDoc.getText().contains("Attachment content, not the body."));

        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        assertEquals("data.txt", attachDoc.getMetadata().get("email.attachmentName"));
        assertTrue(attachDoc.getText().contains("Attachment content, not the body."));
    }

    @Test
    void forwardedMessageRendersAsQuotedSectionAndFoldsNestedAttachments() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage forwardedMsg = createMessageWithTextAttachment(
                "Original Subject", "original@test.com", "someone@test.com",
                "Forwarded body text.", "forwarded.txt", "Nested attachment content.");

        MimeMessage outerMsg = new MimeMessage((Session) null);
        outerMsg.setSubject("Fwd: Original Subject");
        outerMsg.setFrom(new InternetAddress("forwarder@test.com"));
        outerMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@test.com"));

        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart introPart = new MimeBodyPart();
        introPart.setText("Please see forwarded email below.", "UTF-8");
        multipart.addBodyPart(introPart);

        MimeBodyPart forwardPart = new MimeBodyPart();
        forwardPart.setContent(forwardedMsg, "message/rfc822");
        multipart.addBodyPart(forwardPart);

        outerMsg.setContent(multipart);
        outerMsg.saveChanges();
        when(mockFolder.getMessages()).thenReturn(new Message[]{outerMsg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        // 1 outer email + the forwarded message's OWN attachment folded into the outer flat list.
        assertEquals(2, docs.size());

        Document emailDoc = docs.stream()
                .filter(d -> !Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        assertTrue(emailDoc.getText().contains("Please see forwarded email below."));
        assertTrue(emailDoc.getText().contains("---------- Forwarded message ----------"));
        assertTrue(emailDoc.getText().contains("Forwarded body text."));
        assertTrue(emailDoc.getText().contains("Original Subject"));

        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        assertEquals("forwarded.txt", attachDoc.getMetadata().get("email.attachmentName"));
        assertTrue(attachDoc.getText().contains("Nested attachment content."));
    }

    @Test
    void serverModeAttachmentDocumentsCarrySourceAndParentSourcePath() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithTextAttachment(
                "Attach Source Test", "from@test.com", "to@test.com",
                "See attached.", "notes.txt", "Notes body.");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        Document emailDoc = docs.stream()
                .filter(d -> !Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        String parentSourcePath = (String) emailDoc.getMetadata().get("source_path");
        assertNotNull(parentSourcePath);

        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElseThrow();
        assertEquals(parentSourcePath, attachDoc.getMetadata().get("parent_source_path"));
        assertEquals(parentSourcePath + "#attachment/1", attachDoc.getMetadata().get("source_path"));
        assertEquals(parentSourcePath + "#attachment/1", attachDoc.getMetadata().get("source"));
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

    // ── load — C2: newest-first cap enforcement ──────────────────────────

    @Test
    void newestMessagesFirstWhenLimitApplied() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        // Folder#getMessages() returns ascending arrival order; index 4 ("Msg 4") is newest.
        Message[] messages = new Message[5];
        for (int i = 0; i < 5; i++) {
            messages[i] = createPlainTextMessage("Msg " + i, "a@t.com", "b@t.com", "body " + i);
        }
        when(mockFolder.getMessages()).thenReturn(messages);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "messageLimit", 2
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size());
        assertEquals("Msg 4", docs.get(0).getMetadata().get("email.subject"),
                "the newest message (last in arrival order) should be converted first");
        assertEquals("Msg 3", docs.get(1).getMetadata().get("email.subject"));
    }

    @Test
    void maxMessagesAliasAppliesSameCapAsMessageLimit() throws Exception {
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

        // "maxMessages" is the shared crawl-registry cap key (C2); this loader must honor it
        // exactly like its own historical "messageLimit" key.
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "maxMessages", 4
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(4, docs.size());
    }

    @Test
    void messageLimitTakesPrecedenceOverMaxMessagesWhenBothPresent() throws Exception {
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

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("messageLimit", 2);
        meta.put("maxMessages", 8);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size());
    }

    @Test
    void messageLimitIsTotalAcrossFolders() throws Exception {
        Store mockStore = mock(Store.class);
        Folder inboxFolder = mock(Folder.class);
        Folder sentFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(inboxFolder);
        when(mockStore.getFolder("Sent")).thenReturn(sentFolder);
        when(inboxFolder.isOpen()).thenReturn(true);
        when(sentFolder.isOpen()).thenReturn(true);

        Message[] inboxMessages = new Message[5];
        for (int i = 0; i < 5; i++) {
            inboxMessages[i] = createPlainTextMessage("Inbox " + i, "a@t.com", "b@t.com", "body");
        }
        Message[] sentMessages = new Message[5];
        for (int i = 0; i < 5; i++) {
            sentMessages[i] = createPlainTextMessage("Sent " + i, "a@t.com", "b@t.com", "body");
        }
        when(inboxFolder.getMessages()).thenReturn(inboxMessages);
        when(sentFolder.getMessages()).thenReturn(sentMessages);

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("folders", List.of("INBOX", "Sent"));
        meta.put("messageLimit", 7);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        // C2: the cap is a TOTAL across folders, not per-folder — INBOX (fetched first) should
        // consume 5 of the 7, leaving only 2 for Sent.
        assertEquals(7, docs.size());
        long inboxCount = docs.stream().filter(d -> "INBOX".equals(d.getMetadata().get("email.folder"))).count();
        long sentCount = docs.stream().filter(d -> "Sent".equals(d.getMetadata().get("email.folder"))).count();
        assertEquals(5, inboxCount);
        assertEquals(2, sentCount);
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

    @Test
    void extractConfigParsesDateOnlyStartDate() throws Exception {
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
        meta.put("startDate", "2026-09-01"); // date-only, no time component

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFolder).search(any());
    }

    @Test
    void extractConfigParsesLenientStringNumbersAndBooleans() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        Message[] messages = new Message[5];
        for (int i = 0; i < 5; i++) {
            messages[i] = createPlainTextMessage("Msg " + i, "a@t.com", "b@t.com", "body " + i);
        }
        when(mockFolder.getMessages()).thenReturn(messages);

        // The crawl registry may send numbers/booleans as JSON-decoded Strings rather than
        // native types (A3's P2 fix) — every one of these must still be honored.
        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("port", "993");
        meta.put("messageLimit", "2");
        meta.put("includeAttachments", "false");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size());
        verify(mockFactory).connect(argThat(config ->
                config.getPort() == 993 && !config.isIncludeAttachments()));
    }

    @Test
    void extractConfigParsesCaseInsensitiveSecurityAndAuthMode() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("security", "starttls");
        meta.put("authMode", "app-password");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFactory).connect(argThat(config ->
                config.getSecurity() == EmailConnectionConfig.Security.STARTTLS
                        && config.getAuthMode() == EmailConnectionConfig.AuthMode.APP_PASSWORD));
    }

    @Test
    void extractConfigThrowsDescriptiveErrorForInvalidEnumValue() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("security", "bogus-value");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load(desc));
        assertTrue(ex.getMessage().contains("security"));
        assertTrue(ex.getMessage().contains("SSL"));
    }

    @Test
    void pop3DefaultsPortTo995WhenSecurityIsSsl() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        // Regression guard: a P2 bug had POP3 always resolving the IMAP default (993) because
        // the raw builder-default was protocol-unaware.
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.POP3)
                .metadata(Map.of("host", "pop.example.com", "username", "u", "password", "p"))
                .build();

        loader.load(desc);

        verify(mockFactory).connect(argThat(config -> config.getEffectivePort() == 995));
    }

    @Test
    void pop3DefaultsPortTo110WhenSecurityIsNone() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "pop.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("security", "NONE");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.POP3)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFactory).connect(argThat(config -> config.getEffectivePort() == 110));
    }

    @Test
    void pop3DateFilterKeepsUndatedMessagesButDropsMessagesBeforeSince() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage oldMsg = new MimeMessage((Session) null);
        oldMsg.setSubject("Old Message");
        oldMsg.setFrom(new InternetAddress("a@test.com"));
        oldMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("b@test.com"));
        oldMsg.setSentDate(Date.from(Instant.parse("2026-01-01T00:00:00Z")));
        oldMsg.setText("old body", "UTF-8");
        oldMsg.saveChanges();

        MimeMessage newMsg = new MimeMessage((Session) null);
        newMsg.setSubject("New Message");
        newMsg.setFrom(new InternetAddress("a@test.com"));
        newMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("b@test.com"));
        newMsg.setSentDate(Date.from(Instant.parse("2026-07-01T00:00:00Z")));
        newMsg.setText("new body", "UTF-8");
        newMsg.saveChanges();

        // No setSentDate/receivedDate at all — reproduces JavaMail's POP3 provider typically
        // being unable to supply a date. Must be KEPT, not dropped.
        MimeMessage undatedMsg = new MimeMessage((Session) null);
        undatedMsg.setSubject("Undated Message");
        undatedMsg.setFrom(new InternetAddress("a@test.com"));
        undatedMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("b@test.com"));
        undatedMsg.setText("undated body", "UTF-8");
        undatedMsg.saveChanges();

        when(mockFolder.getMessages()).thenReturn(new Message[]{oldMsg, newMsg, undatedMsg});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "pop.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("since", "2026-06-01T00:00:00Z");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.POP3)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        List<Object> subjects = docs.stream().map(d -> d.getMetadata().get("email.subject")).toList();
        assertEquals(2, docs.size());
        assertTrue(subjects.contains("New Message"));
        assertTrue(subjects.contains("Undated Message"));
        assertFalse(subjects.contains("Old Message"));
    }

    // ── load — C3/A4: "since" contract ───────────────────────────────────

    @Test
    void sinceMetadataFiltersOutMessagesBeforeCutoffOnImap() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage oldMsg = new MimeMessage((Session) null);
        oldMsg.setSubject("Old");
        oldMsg.setFrom(new InternetAddress("a@test.com"));
        oldMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("b@test.com"));
        oldMsg.setSentDate(Date.from(Instant.parse("2026-01-01T00:00:00Z")));
        oldMsg.setText("old", "UTF-8");
        oldMsg.saveChanges();

        MimeMessage newMsg = new MimeMessage((Session) null);
        newMsg.setSubject("New");
        newMsg.setFrom(new InternetAddress("a@test.com"));
        newMsg.setRecipient(Message.RecipientType.TO, new InternetAddress("b@test.com"));
        newMsg.setSentDate(Date.from(Instant.parse("2026-07-01T00:00:00Z")));
        newMsg.setText("new", "UTF-8");
        newMsg.saveChanges();

        // IMAP's day-granular server-side search only narrows the candidate set; the mock
        // returns both unfiltered, and the exact bound must still be enforced client-side.
        when(mockFolder.search(any())).thenReturn(new Message[]{oldMsg, newMsg});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("since", "2026-06-01T00:00:00Z");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
        assertEquals("New", docs.get(0).getMetadata().get("email.subject"));
    }

    @Test
    void laterOfSinceAndStartDateWinsAsEffectiveLowerBound() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.search(any())).thenReturn(new Message[]{});

        // since (2026-08-01) is LATER than startDate (2026-01-01) -> since must win.
        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("startDate", "2026-01-01T00:00:00");
        meta.put("since", "2026-08-01T00:00:00Z");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFactory).connect(argThat(config ->
                Instant.parse("2026-08-01T00:00:00Z").equals(config.getEffectiveSinceInstant())));
    }

    // ── load — C4: attachmentDirectory file-write mode ───────────────────

    @Test
    void attachmentDirectoryModeWritesFilesInsteadOfEmittingDocuments() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithTextAttachment(
                "File Write Mode", "from@test.com", "to@test.com",
                "See attached.", "notes.txt", "Saved attachment body.");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("attachmentDirectory", tempDir.toString());

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        // No separate attachment Document in file-write mode.
        assertEquals(1, docs.size());
        Document emailDoc = docs.get(0);
        assertNull(emailDoc.getMetadata().get("email.isAttachment"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) emailDoc.getMetadata().get("attachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        Map<String, Object> entry = attachments.get(0);
        assertEquals("notes.txt", entry.get("fileName"));
        assertEquals("text/plain", entry.get("contentType"));
        assertNull(entry.get("skipped"));
        String relativePath = (String) entry.get("path");
        assertNotNull(relativePath);

        Path savedFile = tempDir.resolve(relativePath);
        assertTrue(Files.exists(savedFile), "attachment file should exist on disk at " + savedFile);
        assertEquals("Saved attachment body.", Files.readString(savedFile).trim());
    }

    @Test
    void attachmentDirectoryModeSkipsOversizedAttachments() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithTextAttachment(
                "Oversized", "from@test.com", "to@test.com",
                "See attached.", "big.txt", "0123456789");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("attachmentDirectory", tempDir.toString());
        meta.put("maxAttachmentBytes", 5L);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) docs.get(0).getMetadata().get("attachments");
        assertEquals(1, attachments.size());
        Map<String, Object> entry = attachments.get(0);
        assertNull(entry.get("path"));
        assertTrue(((String) entry.get("skipped")).contains("exceeds maxAttachmentBytes"));

        try (var stream = Files.walk(tempDir)) {
            assertEquals(0, stream.filter(Files::isRegularFile).count(),
                    "an oversized attachment must never be left on disk");
        }
    }

    @Test
    void attachmentDirectoryModeSkipsInlineCidImages() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject("Signature Logo");
        msg.setFrom(new InternetAddress("from@test.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("to@test.com"));

        MimeMultipart multipart = new MimeMultipart("related");
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent("<html><body>Signed, <img src=\"cid:logo123\"></body></html>", "text/html");
        multipart.addBodyPart(bodyPart);

        MimeBodyPart cidImagePart = new MimeBodyPart();
        cidImagePart.setContent(new byte[]{1, 2, 3, 4}, "image/png");
        cidImagePart.setFileName("logo.png");
        cidImagePart.setHeader("Content-ID", "<logo123>");
        cidImagePart.setDisposition(Part.INLINE);
        multipart.addBodyPart(cidImagePart);

        msg.setContent(multipart);
        msg.saveChanges();
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "user");
        meta.put("password", "pass");
        meta.put("attachmentDirectory", tempDir.toString());
        meta.put("includeHtmlBody", true);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) docs.get(0).getMetadata().get("attachments");
        assertNotNull(attachments);
        assertTrue(attachments.isEmpty(), "an inline Content-ID image must not appear in the attachments list at all");

        try (var stream = Files.walk(tempDir)) {
            assertEquals(0, stream.filter(Files::isRegularFile).count(),
                    "an inline Content-ID image must never be written to disk");
        }
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

    @Test
    void distinctMessagesInSameFolderGetDistinctSourcePaths() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg1 = createPlainTextMessage("First", "a@t.com", "b@t.com", "first body");
        MimeMessage msg2 = createPlainTextMessage("Second", "a@t.com", "b@t.com", "second body");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg1, msg2});

        List<Document> docs = loader.load(descriptorWithDefaults());

        assertEquals(2, docs.size());
        String sourcePath1 = (String) docs.get(0).getMetadata().get("source_path");
        String sourcePath2 = (String) docs.get(1).getMetadata().get("source_path");
        assertNotNull(sourcePath1);
        assertNotNull(sourcePath2);
        assertTrue(sourcePath1.startsWith("imap://imap.example.com/INBOX/"),
                "source_path should start with protocol://host/folder/, was: " + sourcePath1);
        assertTrue(sourcePath2.startsWith("imap://imap.example.com/INBOX/"),
                "source_path should start with protocol://host/folder/, was: " + sourcePath2);
        assertNotEquals(sourcePath1, sourcePath2,
                "two distinct messages in the same folder must not collapse onto the same source_path");
        // No consumer in the repo groups by the folder-level "source" value for EMAIL_INBOX
        // documents, so "source" tracks "source_path" per message (see ImapPopDocumentLoader).
        assertEquals(sourcePath1, docs.get(0).getMetadata().get("source"));
        assertEquals(sourcePath2, docs.get(1).getMetadata().get("source"));
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
