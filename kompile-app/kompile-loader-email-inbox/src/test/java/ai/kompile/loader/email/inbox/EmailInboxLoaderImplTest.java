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

import ai.kompile.core.loaders.DocumentLoader.LoaderProgress;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailInboxLoaderImplTest {

    private EmailInboxLoaderImpl loader;

    @TempDir
    Path tempDir;

    private static final String EML_1 =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: First Email\r\n" +
            "Message-ID: <msg001@example.com>\r\n" +
            "Date: Thu, 15 May 2025 10:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Hello from email one.\r\n";

    private static final String EML_2 =
            "From: carol@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Second Email\r\n" +
            "Message-ID: <msg002@example.com>\r\n" +
            "Date: Thu, 15 May 2025 11:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Hello from email two.\r\n";

    private static final String EML_3 =
            "From: dave@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Third Email\r\n" +
            "Message-ID: <msg003@example.com>\r\n" +
            "Date: Thu, 15 May 2025 12:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Hello from email three.\r\n";

    @BeforeEach
    void setUp() {
        loader = new EmailInboxLoaderImpl();
    }

    // ── supports() tests ──────────────────────────────────────────────────

    @Test
    void supportsMboxSourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MBOX)
                .pathOrUrl("/some/path/inbox.mbox")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsMaildirSourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl("/some/path/Maildir")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsFileTypeWithMboxExtension() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/some/path/inbox.mbox")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsFileTypeWithMbxExtension() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/some/path/inbox.mbx")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsDirectoryTypeWhenMaildir() throws IOException {
        // Create a Maildir structure
        Path maildir = createMaildir(tempDir, "testmaildir");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.DIRECTORY)
                .pathOrUrl(maildir.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void doesNotSupportDirectoryWithoutMaildirStructure() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.DIRECTORY)
                .pathOrUrl(tempDir.toString())
                .build();
        assertFalse(loader.supports(desc));
    }

    @Test
    void doesNotSupportNonEmailFileType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/some/path/document.pdf")
                .build();
        assertFalse(loader.supports(desc));
    }

    @Test
    void doesNotSupportUrlType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.URL)
                .pathOrUrl("http://example.com")
                .build();
        assertFalse(loader.supports(desc));
    }

    // ── mbox loading ──────────────────────────────────────────────────────

    @Test
    void loadsMboxFileWithMultipleMessages() throws Exception {
        Path mboxFile = createMboxFile(tempDir, "inbox.mbox", EML_1, EML_2, EML_3);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MBOX)
                .pathOrUrl(mboxFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(3, docs.size(), "Should load 3 messages from mbox");

        // Verify each message was parsed
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("First Email")));
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("Second Email")));
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("Third Email")));

        // Verify mbox metadata
        for (Document doc : docs) {
            assertNotNull(doc.getMetadata().get("email.mboxIndex"));
            assertEquals(mboxFile.toString(), doc.getMetadata().get("email.mboxFile"));
        }
    }

    @Test
    void loadsMboxFileWithMessageLimit() throws Exception {
        Path mboxFile = createMboxFile(tempDir, "inbox.mbox", EML_1, EML_2, EML_3);

        Map<String, Object> meta = new HashMap<>();
        meta.put("messageLimit", 2);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MBOX)
                .pathOrUrl(mboxFile.toString())
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size(), "Should respect message limit");
    }

    @Test
    void mboxLoadReportsProgress() throws Exception {
        Path mboxFile = createMboxFile(tempDir, "big.mbox",
                generateEmails(150)); // 150 messages to trigger progress at 100

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MBOX)
                .pathOrUrl(mboxFile.toString())
                .build();

        List<LoaderProgress> progressReports = new ArrayList<>();
        loader.load(desc, progressReports::add);

        assertFalse(progressReports.isEmpty(), "Should report progress for >100 messages");
        assertTrue(progressReports.stream()
                .allMatch(p -> p.phase().equals("Parsing mbox")));
    }

    @Test
    void mboxLoadThrowsForMissingFile() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MBOX)
                .pathOrUrl("/nonexistent/inbox.mbox")
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(desc));
    }

    // ── Maildir loading ───────────────────────────────────────────────────

    @Test
    void loadsMaildirWithCurAndNewMessages() throws Exception {
        Path maildir = createMaildir(tempDir, "Maildir");
        writeEmlFile(maildir.resolve("cur"), "1234567890.M1:2,S", EML_1);
        writeEmlFile(maildir.resolve("new"), "1234567891.M2", EML_2);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(maildir.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size(), "Should load messages from both cur and new");

        // Check folder metadata
        assertTrue(docs.stream().allMatch(d -> "INBOX".equals(d.getMetadata().get("email.folder"))));

        // Check subdir metadata
        assertTrue(docs.stream().anyMatch(d -> "cur".equals(d.getMetadata().get("email.maildirSubdir"))));
        assertTrue(docs.stream().anyMatch(d -> "new".equals(d.getMetadata().get("email.maildirSubdir"))));
    }

    @Test
    void loadsMaildirPlusPlusSubfolders() throws Exception {
        Path root = tempDir.resolve("Maildir");
        Files.createDirectories(root);

        // Create INBOX
        createMaildir(root, "");
        writeEmlFile(root.resolve("cur"), "1.M1:2,S", EML_1);

        // Create .Sent subfolder (Maildir++ convention)
        Path sentDir = createMaildir(root, ".Sent");
        writeEmlFile(sentDir.resolve("cur"), "2.M2:2,S", EML_2);

        // Create .Trash subfolder
        Path trashDir = createMaildir(root, ".Trash");
        writeEmlFile(trashDir.resolve("cur"), "3.M3:2,ST", EML_3);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(root.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertTrue(docs.size() >= 3, "Should load from INBOX + Sent + Trash");
        assertTrue(docs.stream().anyMatch(d -> "INBOX".equals(d.getMetadata().get("email.folder"))));
        assertTrue(docs.stream().anyMatch(d -> "Sent".equals(d.getMetadata().get("email.folder"))));
        assertTrue(docs.stream().anyMatch(d -> "Trash".equals(d.getMetadata().get("email.folder"))));
    }

    @Test
    void maildirLoadWithFolderFilter() throws Exception {
        Path root = tempDir.resolve("FilterMaildir");
        Files.createDirectories(root);

        createMaildir(root, "");
        writeEmlFile(root.resolve("cur"), "1.M1:2,S", EML_1);

        Path sentDir = createMaildir(root, ".Sent");
        writeEmlFile(sentDir.resolve("cur"), "2.M2:2,S", EML_2);

        Path trashDir = createMaildir(root, ".Trash");
        writeEmlFile(trashDir.resolve("cur"), "3.M3:2,ST", EML_3);

        Map<String, Object> meta = new HashMap<>();
        meta.put("folders", "Sent");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(root.toString())
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size(), "Should only load Sent folder");
        assertEquals("Sent", docs.get(0).getMetadata().get("email.folder"));
    }

    @Test
    void maildirLoadSkipsHiddenFiles() throws Exception {
        Path maildir = createMaildir(tempDir, "HiddenTest");
        writeEmlFile(maildir.resolve("cur"), "visible:2,S", EML_1);
        writeEmlFile(maildir.resolve("cur"), ".hidden-file", EML_2);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(maildir.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size(), "Should skip hidden files");
    }

    @Test
    void maildirLoadReportsProgress() throws Exception {
        Path maildir = createMaildir(tempDir, "ProgressMaildir");
        // Create 60 emails to trigger progress at 50
        for (int i = 0; i < 60; i++) {
            writeEmlFile(maildir.resolve("cur"), i + ".M" + i + ":2,S",
                    "From: u" + i + "@test.com\r\nSubject: Msg " + i +
                    "\r\nContent-Type: text/plain\r\n\r\nBody " + i + "\r\n");
        }

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(maildir.toString())
                .build();

        List<LoaderProgress> progress = new ArrayList<>();
        loader.load(desc, progress::add);

        assertFalse(progress.isEmpty(), "Should report progress for >50 messages");
    }

    // ── Maildir flag parsing ──────────────────────────────────────────────

    @Test
    void parsesMaildirFlags() {
        assertEquals("S", EmailInboxLoaderImpl.parseMaildirFlags("1234567890.M1:2,S"));
        assertEquals("SR", EmailInboxLoaderImpl.parseMaildirFlags("1234567890.M1:2,SR"));
        assertEquals("FRST", EmailInboxLoaderImpl.parseMaildirFlags("msg:2,FRST"));
        assertEquals("", EmailInboxLoaderImpl.parseMaildirFlags("1234567890.M1"));
        assertEquals("", EmailInboxLoaderImpl.parseMaildirFlags("no-flags-here"));
    }

    @Test
    void maildirFlagsInMetadata() throws Exception {
        Path maildir = createMaildir(tempDir, "FlagsMaildir");
        writeEmlFile(maildir.resolve("cur"), "1234567890.M1:2,SR", EML_1);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.MAILDIR)
                .pathOrUrl(maildir.toString())
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals(1, docs.size());
        assertEquals("SR", docs.get(0).getMetadata().get("email.maildirFlags"));
    }

    // ── isMaildir detection ───────────────────────────────────────────────

    @Test
    void isMaildirDetectsCurAndNew() throws IOException {
        Path maildir = createMaildir(tempDir, "DetectMaildir");
        assertTrue(EmailInboxLoaderImpl.isMaildir(maildir));
    }

    @Test
    void isMaildirRejectsMissingSubdirs() {
        assertFalse(EmailInboxLoaderImpl.isMaildir(tempDir));
    }

    @Test
    void isMaildirRejectsFile() throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "not a directory");
        assertFalse(EmailInboxLoaderImpl.isMaildir(file));
    }

    // ── PST source type support ─────────────────────────────────────────

    @Test
    void supportsPstSourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.PST)
                .pathOrUrl("/some/path/outlook.pst")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsFileTypeWithPstExtension() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/some/path/archive.pst")
                .build();
        assertTrue(loader.supports(desc));
    }

    // ── EMLX source type support ─────────────────────────────────────────

    @Test
    void supportsEmlxDirSourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.EMLX_DIR)
                .pathOrUrl("/some/path/Mail/V10/account")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsFileTypeWithEmlxExtension() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/some/path/message.emlx")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsDirectoryTypeWhenAppleMailDir() throws IOException {
        // Create Apple Mail bundle structure
        Path accountDir = tempDir.resolve("AppleMailDir");
        Path messagesDir = accountDir.resolve("INBOX.mbox").resolve("Messages");
        Files.createDirectories(messagesDir);
        Files.writeString(messagesDir.resolve("1.emlx"),
                "42\nFrom: a@b.com\r\nSubject: Hi\r\nContent-Type: text/plain\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.DIRECTORY)
                .pathOrUrl(accountDir.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    // ── EMLX loading ─────────────────────────────────────────────────────

    @Test
    void loadsEmlxDirectory() throws Exception {
        // Create Apple Mail account dir with 2 mailbox bundles
        Path accountDir = tempDir.resolve("EmlxLoadDir");
        createEmlxBundle(accountDir, "INBOX.mbox", "From: alice@example.com\r\n" +
                "Subject: EMLX Test\r\nContent-Type: text/plain\r\n\r\nHello from EMLX.\r\n");
        createEmlxBundle(accountDir, "Sent.mbox", "From: bob@example.com\r\n" +
                "Subject: Sent Test\r\nContent-Type: text/plain\r\n\r\nSent message.\r\n");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.EMLX_DIR)
                .pathOrUrl(accountDir.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size());
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("EMLX Test")));
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("Sent Test")));

        // Verify EMLX format metadata
        for (Document doc : docs) {
            assertEquals("emlx", doc.getMetadata().get("email.format"));
        }
    }

    @Test
    void loadsSingleEmlxFile() throws Exception {
        String email = "From: alice@example.com\r\n" +
                "Subject: Single EMLX\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Single file test.\r\n";
        byte[] emailBytes = email.getBytes(StandardCharsets.UTF_8);
        String emlxContent = emailBytes.length + "\n" + email;

        Path emlxFile = tempDir.resolve("single.emlx");
        Files.writeString(emlxFile, emlxContent, StandardCharsets.UTF_8);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(emlxFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getText().contains("Single EMLX"));
    }

    @Test
    void loadsEmlxDirectoryWithMessageLimit() throws Exception {
        Path accountDir = tempDir.resolve("LimitedEmlx");
        Path messagesDir = accountDir.resolve("All.mbox").resolve("Messages");
        Files.createDirectories(messagesDir);
        for (int i = 0; i < 10; i++) {
            String email = "From: user" + i + "@example.com\r\n" +
                    "Subject: Msg " + i + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "Body " + i + "\r\n";
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            Files.writeString(messagesDir.resolve((i + 1) + ".emlx"),
                    bytes.length + "\n" + email, StandardCharsets.UTF_8);
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("messageLimit", 3);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.EMLX_DIR)
                .pathOrUrl(accountDir.toString())
                .metadata(meta)
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(3, docs.size(), "Should respect message limit for EMLX dirs");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void createEmlxBundle(Path parent, String bundleName, String emailContent) throws IOException {
        Path messagesDir = parent.resolve(bundleName).resolve("Messages");
        Files.createDirectories(messagesDir);
        byte[] bytes = emailContent.getBytes(StandardCharsets.UTF_8);
        Files.writeString(messagesDir.resolve("1.emlx"),
                bytes.length + "\n" + emailContent, StandardCharsets.UTF_8);
    }


    private Path createMaildir(Path parent, String name) throws IOException {
        Path maildir = name.isEmpty() ? parent : parent.resolve(name);
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        return maildir;
    }

    private void writeEmlFile(Path dir, String filename, String content) throws IOException {
        Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
    }

    private Path createMboxFile(Path dir, String filename, String... emails) throws IOException {
        StringBuilder mbox = new StringBuilder();
        for (String email : emails) {
            mbox.append("From sender@example.com Thu May 15 10:00:00 2025\r\n");
            mbox.append(email);
            mbox.append("\r\n");
        }
        Path file = dir.resolve(filename);
        Files.writeString(file, mbox.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private String[] generateEmails(int count) {
        String[] emails = new String[count];
        for (int i = 0; i < count; i++) {
            emails[i] = "From: user" + i + "@test.com\r\n" +
                    "Subject: Message " + i + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "Body of message " + i + ".\r\n";
        }
        return emails;
    }
}
