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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests that ImapPopDocumentLoader preserves raw HTML in email.htmlBody metadata
 * when loading emails via IMAP/POP3.
 */
class ImapHtmlPreservationTest {

    private ImapPopDocumentLoader loader;
    private EmailConnectionFactory mockFactory;
    private Store mockStore;
    private Folder mockFolder;

    @BeforeEach
    void setUp() throws Exception {
        mockFactory = mock(EmailConnectionFactory.class);
        mockStore = mock(Store.class);
        mockFolder = mock(Folder.class);

        when(mockFactory.connect(any(EmailConnectionConfig.class))).thenReturn(mockStore);
        when(mockStore.getFolder(anyString())).thenReturn(mockFolder);
        when(mockFolder.exists()).thenReturn(true);
        when(mockFolder.isOpen()).thenReturn(false).thenReturn(true);

        loader = new ImapPopDocumentLoader(mockFactory);
    }

    // ── HTML-only message preserves raw HTML ────────────────────────────────

    @Test
    void htmlOnlyMessagePreservesRawHtml() throws Exception {
        String htmlContent = "<html><body><h1>Important</h1><p>Meeting at <b>3pm</b>.</p></body></html>";
        MimeMessage msg = createHtmlMessage("HTML Email", "sender@test.com", "inbox@test.com", htmlContent);

        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(buildDescriptor(true));
        assertFalse(docs.isEmpty());

        Document doc = docs.get(0);
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "email.htmlBody should be present for HTML-only messages");
        assertTrue(rawHtml.contains("<h1>Important</h1>"));
        assertTrue(rawHtml.contains("<b>3pm</b>"));

        // Display text should be converted
        String text = doc.getText();
        assertTrue(text.contains("Important"));
        assertTrue(text.contains("3pm"));
        assertFalse(text.contains("<h1>"), "Display text should not contain HTML tags");
    }

    // ── Multipart/alternative preserves HTML part ───────────────────────────

    @Test
    void multipartAlternativePreservesHtml() throws Exception {
        String plainContent = "Meeting at 3pm.";
        String htmlContent = "<html><body><p>Meeting at <b>3pm</b>.</p></body></html>";
        MimeMessage msg = createMultipartAlternativeMessage(
                "Alt Email", "sender@test.com", "inbox@test.com",
                plainContent, htmlContent);

        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(buildDescriptor(true));
        assertFalse(docs.isEmpty());

        Document doc = docs.get(0);

        // Display text should prefer plain text
        assertTrue(doc.getText().contains("Meeting at 3pm"));

        // Raw HTML should still be in metadata
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "email.htmlBody should be present in multipart/alternative");
        assertTrue(rawHtml.contains("<b>3pm</b>"));
    }

    // ── Plain-text-only message has no htmlBody ─────────────────────────────

    @Test
    void plainTextMessageHasNoHtmlBody() throws Exception {
        MimeMessage msg = createPlainTextMessage(
                "Plain Email", "sender@test.com", "inbox@test.com", "Just text.");

        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(buildDescriptor(false));
        assertFalse(docs.isEmpty());

        Document doc = docs.get(0);
        assertNull(doc.getMetadata().get("email.htmlBody"),
                "Plain text email should not have email.htmlBody");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DocumentSourceDescriptor buildDescriptor(boolean includeHtml) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.test.com");
        meta.put("port", 993);
        meta.put("username", "user");
        meta.put("password", "pass");
        if (includeHtml) {
            meta.put("includeHtmlBody", true);
        }

        return DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .pathOrUrl("imap.test.com")
                .metadata(meta)
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

    private MimeMessage createHtmlMessage(String subject, String from, String to, String htmlBody)
            throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());
        msg.setContent(htmlBody, "text/html; charset=UTF-8");
        msg.saveChanges();
        return msg;
    }

    private MimeMessage createMultipartAlternativeMessage(String subject, String from, String to,
                                                           String plainBody, String htmlBody)
            throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());

        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plainBody, "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        multipart.addBodyPart(textPart);
        multipart.addBodyPart(htmlPart);

        msg.setContent(multipart);
        msg.saveChanges();
        return msg;
    }
}
