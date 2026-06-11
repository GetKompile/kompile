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
 * Integration tests verifying that {@link MailLoaderImpl} stores email headers under the
 * {@code email.*} metadata namespace introduced in the latest refactor.
 *
 * <p>Each test writes a minimal RFC 822 .eml file to a temporary directory, loads it via
 * {@link MailLoaderImpl#load(DocumentSourceDescriptor)}, and asserts the correct namespaced
 * key is present in the returned {@link Document}'s metadata map.
 */
class MailLoaderImplMetadataTest {

    private final MailLoaderImpl loader = new MailLoaderImpl();

    // =========================================================================
    // Helper
    // =========================================================================

    private DocumentSourceDescriptor descriptorFor(Path emlFile) {
        return DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(emlFile.toAbsolutePath().toString())
                .build();
    }

    private List<Document> loadEml(String emlContent, Path tempDir) throws Exception {
        Path emlFile = tempDir.resolve("test.eml");
        Files.writeString(emlFile, emlContent);
        return loader.load(descriptorFor(emlFile));
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * Verifies that the From header is stored under {@code email.from} (not the bare key
     * {@code from} used before the refactor).
     */
    @Test
    void loadEml_setsEmailFromMetadata(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Hello\r\n"
                + "Date: Mon, 1 Jan 2025 12:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Email body text.\r\n";

        List<Document> documents = loadEml(eml, tempDir);

        assertFalse(documents.isEmpty(), "Expected at least one document");
        Map<String, Object> metadata = documents.get(0).getMetadata();

        assertTrue(metadata.containsKey("email.from"),
                "Metadata must contain 'email.from' key (not bare 'from')");
        assertFalse(metadata.containsKey("from"),
                "Bare 'from' key must not be present — use 'email.from' namespace");

        String from = (String) metadata.get("email.from");
        assertNotNull(from, "email.from value must not be null");
        assertTrue(from.contains("alice@example.com"),
                "email.from should contain the sender address, got: " + from);
    }

    /**
     * Verifies that the To and Cc headers are stored under {@code email.to} and
     * {@code email.cc} respectively (both were missing before the refactor).
     */
    @Test
    void loadEml_setsEmailToAndCcMetadata(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Cc: Charlie <charlie@example.com>\r\n"
                + "Subject: Group Email\r\n"
                + "Date: Tue, 2 Jan 2025 09:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "A group email.\r\n";

        List<Document> documents = loadEml(eml, tempDir);

        assertFalse(documents.isEmpty(), "Expected at least one document");
        Map<String, Object> metadata = documents.get(0).getMetadata();

        assertTrue(metadata.containsKey("email.to"),
                "Metadata must contain 'email.to' key");
        String to = (String) metadata.get("email.to");
        assertNotNull(to, "email.to value must not be null");
        assertTrue(to.contains("bob@example.com"),
                "email.to should contain the recipient address, got: " + to);

        assertTrue(metadata.containsKey("email.cc"),
                "Metadata must contain 'email.cc' key");
        String cc = (String) metadata.get("email.cc");
        assertNotNull(cc, "email.cc value must not be null");
        assertTrue(cc.contains("charlie@example.com"),
                "email.cc should contain the CC address, got: " + cc);
    }

    /**
     * Verifies that the Subject header is stored under {@code email.subject} (not the bare
     * key {@code subject} used before the refactor).
     */
    @Test
    void loadEml_setsEmailSubjectMetadata(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Test Email Subject\r\n"
                + "Date: Wed, 3 Jan 2025 10:30:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Testing subject metadata.\r\n";

        List<Document> documents = loadEml(eml, tempDir);

        assertFalse(documents.isEmpty(), "Expected at least one document");
        Map<String, Object> metadata = documents.get(0).getMetadata();

        assertTrue(metadata.containsKey("email.subject"),
                "Metadata must contain 'email.subject' key (not bare 'subject')");
        assertFalse(metadata.containsKey("subject"),
                "Bare 'subject' key must not be present — use 'email.subject' namespace");

        String subject = (String) metadata.get("email.subject");
        assertEquals("Test Email Subject", subject,
                "email.subject should match the Subject header value exactly");
    }

    /**
     * Verifies that the Date header is stored under {@code email.date} (not the old key
     * {@code messageDate} used before the refactor).
     */
    @Test
    void loadEml_setsEmailDateMetadata(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Date Test\r\n"
                + "Date: Thu, 4 Jan 2025 15:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Testing date metadata.\r\n";

        List<Document> documents = loadEml(eml, tempDir);

        assertFalse(documents.isEmpty(), "Expected at least one document");
        Map<String, Object> metadata = documents.get(0).getMetadata();

        assertTrue(metadata.containsKey("email.date"),
                "Metadata must contain 'email.date' key (not old 'messageDate' key)");
        assertFalse(metadata.containsKey("messageDate"),
                "Old 'messageDate' key must not be present — use 'email.date' namespace");

        String date = (String) metadata.get("email.date");
        assertNotNull(date, "email.date value must not be null");
        assertFalse(date.isBlank(), "email.date value must not be blank");
    }
}
