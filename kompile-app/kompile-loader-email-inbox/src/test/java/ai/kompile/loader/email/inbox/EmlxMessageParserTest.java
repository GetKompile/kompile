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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmlxMessageParserTest {

    private EmlxMessageParser parser;

    @TempDir
    Path tempDir;

    private static final String SIMPLE_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Test EMLX\r\n" +
            "Message-ID: <emlx001@example.com>\r\n" +
            "Date: Thu, 15 May 2025 10:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Hello from an EMLX message.\r\n";

    private static final String APPLE_PLIST_TRAILER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
            "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n" +
            "<dict>\n" +
            "\t<key>flags</key>\n" +
            "\t<integer>8590195713</integer>\n" +
            "</dict>\n" +
            "</plist>\n";

    @BeforeEach
    void setUp() {
        parser = new EmlxMessageParser();
    }

    // ── Single .emlx file parsing ────────────────────────────────────────

    @Test
    void parsesEmlxWithByteCountPrefix() throws IOException {
        byte[] messageBytes = SIMPLE_EMAIL.getBytes(StandardCharsets.UTF_8);
        String emlxContent = messageBytes.length + "\n" + SIMPLE_EMAIL + APPLE_PLIST_TRAILER;

        Path emlxFile = tempDir.resolve("1.emlx");
        Files.writeString(emlxFile, emlxContent, StandardCharsets.UTF_8);

        List<Document> docs = parser.parseEmlxFile(emlxFile, emlxFile.toString());

        assertEquals(1, docs.size());
        Document doc = docs.get(0);
        assertTrue(doc.getText().contains("Test EMLX"));
        assertTrue(doc.getText().contains("Hello from an EMLX message"));
        assertEquals("emlx", doc.getMetadata().get("email.format"));
    }

    @Test
    void parsesEmlxWithoutPlistTrailer() throws IOException {
        byte[] messageBytes = SIMPLE_EMAIL.getBytes(StandardCharsets.UTF_8);
        String emlxContent = messageBytes.length + "\n" + SIMPLE_EMAIL;

        Path emlxFile = tempDir.resolve("2.emlx");
        Files.writeString(emlxFile, emlxContent, StandardCharsets.UTF_8);

        List<Document> docs = parser.parseEmlxFile(emlxFile, emlxFile.toString());

        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getText().contains("Hello from an EMLX message"));
    }

    @Test
    void fallsBackToDirectParseIfNoValidByteCount() throws IOException {
        // Write a regular .eml file as .emlx (no byte-count prefix)
        Path emlxFile = tempDir.resolve("nopre.emlx");
        Files.writeString(emlxFile, SIMPLE_EMAIL, StandardCharsets.UTF_8);

        List<Document> docs = parser.parseEmlxFile(emlxFile, emlxFile.toString());

        // Should still parse successfully via fallback
        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getText().contains("Hello from an EMLX message"));
    }

    @Test
    void returnsEmptyForInvalidEmlxFile() throws IOException {
        // File with no newline at all
        Path emlxFile = tempDir.resolve("bad.emlx");
        Files.writeString(emlxFile, "no-newline-here", StandardCharsets.UTF_8);

        List<Document> docs = parser.parseEmlxFile(emlxFile, emlxFile.toString());

        assertTrue(docs.isEmpty());
    }

    @Test
    void tagsDocumentsWithEmlxFormat() throws IOException {
        byte[] messageBytes = SIMPLE_EMAIL.getBytes(StandardCharsets.UTF_8);
        String emlxContent = messageBytes.length + "\n" + SIMPLE_EMAIL;

        Path emlxFile = tempDir.resolve("tagged.emlx");
        Files.writeString(emlxFile, emlxContent, StandardCharsets.UTF_8);

        List<Document> docs = parser.parseEmlxFile(emlxFile, emlxFile.toString());

        assertEquals(1, docs.size());
        assertEquals("emlx", docs.get(0).getMetadata().get("email.format"));
    }

    // ── Mailbox bundle loading ───────────────────────────────────────────

    @Test
    void loadsMailboxBundle() throws IOException {
        // Create Apple Mail bundle structure: Inbox.mbox/Messages/*.emlx
        Path mboxBundle = tempDir.resolve("Inbox.mbox");
        Path messagesDir = mboxBundle.resolve("Messages");
        Files.createDirectories(messagesDir);

        for (int i = 1; i <= 3; i++) {
            String email = "From: user" + i + "@example.com\r\n" +
                    "To: inbox@example.com\r\n" +
                    "Subject: Message " + i + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "Body " + i + "\r\n";
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            String content = bytes.length + "\n" + email;
            Files.writeString(messagesDir.resolve(i + ".emlx"), content, StandardCharsets.UTF_8);
        }

        List<Document> docs = parser.loadMailboxBundle(mboxBundle, "Inbox");

        assertEquals(3, docs.size());
        for (Document doc : docs) {
            assertEquals("Inbox", doc.getMetadata().get("email.folder"));
            assertEquals("emlx", doc.getMetadata().get("email.format"));
        }
    }

    @Test
    void loadMailboxBundleSkipsNonEmlxFiles() throws IOException {
        Path mboxBundle = tempDir.resolve("Mixed.mbox");
        Path messagesDir = mboxBundle.resolve("Messages");
        Files.createDirectories(messagesDir);

        // Create a valid .emlx
        String email = "From: a@b.com\r\nSubject: Valid\r\nContent-Type: text/plain\r\n\r\nBody\r\n";
        byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
        Files.writeString(messagesDir.resolve("1.emlx"), bytes.length + "\n" + email, StandardCharsets.UTF_8);

        // Create a non-.emlx file
        Files.writeString(messagesDir.resolve("index.dat"), "some index data", StandardCharsets.UTF_8);

        List<Document> docs = parser.loadMailboxBundle(mboxBundle, "Mixed");

        assertEquals(1, docs.size());
    }

    @Test
    void loadMailboxBundleReturnsEmptyWithNoMessagesDir() throws IOException {
        Path mboxBundle = tempDir.resolve("Empty.mbox");
        Files.createDirectories(mboxBundle);
        // No Messages/ subdirectory

        List<Document> docs = parser.loadMailboxBundle(mboxBundle, "Empty");

        assertTrue(docs.isEmpty());
    }

    // ── Account directory loading ────────────────────────────────────────

    @Test
    void loadsAccountDirectoryWithNestedBundles() throws IOException {
        // Simulate Apple Mail account directory:
        //   accountDir/
        //     INBOX.mbox/Messages/1.emlx
        //     Sent Messages.mbox/Messages/2.emlx
        //     Trash.mbox/Messages/3.emlx
        Path accountDir = tempDir.resolve("AccountDir");
        Files.createDirectories(accountDir);

        createEmlxBundle(accountDir, "INBOX.mbox", 2);
        createEmlxBundle(accountDir, "Sent Messages.mbox", 1);
        createEmlxBundle(accountDir, "Trash.mbox", 1);

        List<Document> docs = parser.loadAccountDirectory(accountDir);

        assertEquals(4, docs.size());

        // Verify folder names are derived from bundle names
        assertTrue(docs.stream().anyMatch(d -> "INBOX".equals(d.getMetadata().get("email.folder"))));
        assertTrue(docs.stream().anyMatch(d -> "Sent Messages".equals(d.getMetadata().get("email.folder"))));
        assertTrue(docs.stream().anyMatch(d -> "Trash".equals(d.getMetadata().get("email.folder"))));
    }

    // ── isAppleMailDirectory ─────────────────────────────────────────────

    @Test
    void detectsAppleMailDirectory() throws IOException {
        Path accountDir = tempDir.resolve("AppleMail");
        createEmlxBundle(accountDir, "INBOX.mbox", 1);

        assertTrue(EmlxMessageParser.isAppleMailDirectory(accountDir));
    }

    @Test
    void rejectsNonAppleMailDirectory() {
        assertFalse(EmlxMessageParser.isAppleMailDirectory(tempDir));
    }

    @Test
    void rejectsFileAsAppleMailDirectory() throws IOException {
        Path file = tempDir.resolve("somefile.txt");
        Files.writeString(file, "not a directory");
        assertFalse(EmlxMessageParser.isAppleMailDirectory(file));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void createEmlxBundle(Path parent, String bundleName, int messageCount) throws IOException {
        Path messagesDir = parent.resolve(bundleName).resolve("Messages");
        Files.createDirectories(messagesDir);

        for (int i = 1; i <= messageCount; i++) {
            String email = "From: user" + i + "@example.com\r\n" +
                    "To: inbox@example.com\r\n" +
                    "Subject: Msg " + i + " in " + bundleName + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "Body " + i + "\r\n";
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            Files.writeString(messagesDir.resolve(i + ".emlx"),
                    bytes.length + "\n" + email, StandardCharsets.UTF_8);
        }
    }
}
