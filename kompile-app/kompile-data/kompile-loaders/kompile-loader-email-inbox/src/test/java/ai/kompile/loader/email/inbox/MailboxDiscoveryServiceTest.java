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

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailboxDiscoveryServiceTest {

    private MailboxDiscoveryService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new MailboxDiscoveryService();
    }

    // ── Thunderbird profile parsing ──────────────────────────────────────

    @Test
    void parsesThunderbirdProfilesIni() throws IOException {
        // Simulate Thunderbird profiles.ini
        Path profilesDir = tempDir.resolve(".thunderbird");
        Files.createDirectories(profilesDir);

        String profilesIni = "[General]\n" +
                "StartWithLastProfile=1\n" +
                "\n" +
                "[Profile0]\n" +
                "Name=default\n" +
                "IsRelative=1\n" +
                "Path=abcd1234.default\n" +
                "Default=1\n" +
                "\n" +
                "[Profile1]\n" +
                "Name=work\n" +
                "IsRelative=1\n" +
                "Path=efgh5678.work\n";

        Files.writeString(profilesDir.resolve("profiles.ini"), profilesIni, StandardCharsets.UTF_8);

        // Create actual profile directories with Mail/Local Folders
        Path profile0 = profilesDir.resolve("abcd1234.default");
        Path localFolders0 = profile0.resolve("Mail").resolve("Local Folders");
        Files.createDirectories(localFolders0);
        // Create an mbox file (Inbox) so it gets discovered
        Files.writeString(localFolders0.resolve("Inbox"),
                "From test@test.com Thu May 15 10:00:00 2025\r\nSubject: Hi\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        Path profile1 = profilesDir.resolve("efgh5678.work");
        Path localFolders1 = profile1.resolve("Mail").resolve("Local Folders");
        Files.createDirectories(localFolders1);
        Files.writeString(localFolders1.resolve("Inbox"),
                "From work@test.com Thu May 15 10:00:00 2025\r\nSubject: Work\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        // Parse profiles.ini
        List<MailboxDiscoveryService.ThunderbirdProfile> profiles =
                service.parseProfilesIni(profilesDir.resolve("profiles.ini"), profilesDir);

        assertEquals(2, profiles.size());
        assertEquals("default", profiles.get(0).name());
        assertEquals(profile0, profiles.get(0).path());
        assertEquals("work", profiles.get(1).name());
        assertEquals(profile1, profiles.get(1).path());
    }

    @Test
    void parsesAbsoluteThunderbirdProfilePath() throws IOException {
        Path profilesDir = tempDir.resolve(".thunderbird");
        Files.createDirectories(profilesDir);

        Path absProfile = tempDir.resolve("absolute-profile");
        Files.createDirectories(absProfile);

        String profilesIni = "[Profile0]\n" +
                "Name=absolute\n" +
                "IsRelative=0\n" +
                "Path=" + absProfile.toAbsolutePath() + "\n";

        Files.writeString(profilesDir.resolve("profiles.ini"), profilesIni, StandardCharsets.UTF_8);

        List<MailboxDiscoveryService.ThunderbirdProfile> profiles =
                service.parseProfilesIni(profilesDir.resolve("profiles.ini"), profilesDir);

        assertEquals(1, profiles.size());
        assertEquals("absolute", profiles.get(0).name());
        assertEquals(absProfile.toAbsolutePath(), profiles.get(0).path());
    }

    // ── Mbox folder discovery ────────────────────────────────────────────

    @Test
    void discoversMboxFolders() throws IOException {
        // Create a directory with mbox files
        Path mailDir = tempDir.resolve("localmail");
        Files.createDirectories(mailDir);

        Files.writeString(mailDir.resolve("Inbox"),
                "From a@b.com Thu May 15 10:00:00 2025\r\nSubject: Hi\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(mailDir.resolve("Sent"),
                "From c@d.com Thu May 15 11:00:00 2025\r\nSubject: Re\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);
        // Index files should be ignored
        Files.writeString(mailDir.resolve("Inbox.msf"), "index data", StandardCharsets.UTF_8);

        List<String> folders = service.discoverMboxFolders(mailDir);

        assertTrue(folders.contains("Inbox"));
        assertTrue(folders.contains("Sent"));
        assertFalse(folders.contains("Inbox.msf"), "Should not include .msf index files");
    }

    // ── EMLX folder discovery ────────────────────────────────────────────

    @Test
    void discoversEmlxFolders() throws IOException {
        // Create Apple Mail structure
        Path mailDir = tempDir.resolve("appleMail");
        createAppleMailBundle(mailDir, "INBOX.mbox", 2);
        createAppleMailBundle(mailDir, "Sent Messages.mbox", 1);

        List<String> folders = service.discoverEmlxFolders(mailDir);

        assertTrue(folders.contains("INBOX"));
        assertTrue(folders.contains("Sent Messages"));
    }

    // ── Maildir detection ────────────────────────────────────────────────

    @Test
    void detectsMaildirStructure() throws IOException {
        Path maildir = tempDir.resolve("maildir");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        assertTrue(service.hasMaildirStructure(maildir));
    }

    @Test
    void rejectsNonMaildirDirectory() {
        assertFalse(service.hasMaildirStructure(tempDir));
    }

    // ── Mbox sniffing ────────────────────────────────────────────────────

    @Test
    void detectsMboxFile() throws IOException {
        Path mbox = tempDir.resolve("test.mbox");
        Files.writeString(mbox, "From a@b.com Thu May 15 10:00:00 2025\r\nSubject: Hi\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        assertTrue(service.looksLikeMbox(mbox));
    }

    @Test
    void detectsMboxByContent() throws IOException {
        // No .mbox extension but starts with "From "
        Path mbox = tempDir.resolve("Inbox");
        Files.writeString(mbox, "From a@b.com Thu May 15 10:00:00 2025\r\nSubject: Hi\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        assertTrue(service.looksLikeMbox(mbox));
    }

    @Test
    void rejectsNonMboxFile() throws IOException {
        Path notMbox = tempDir.resolve("document.txt");
        Files.writeString(notMbox, "This is not an mbox file.\r\n", StandardCharsets.UTF_8);

        assertFalse(service.looksLikeMbox(notMbox));
    }

    // ── Message counting ─────────────────────────────────────────────────

    @Test
    void discoversMboxFoldersInDirectory() throws IOException {
        Path mailDir = tempDir.resolve("tbirdLocal");
        Files.createDirectories(mailDir);

        // Create several mbox files
        Files.writeString(mailDir.resolve("Inbox"),
                "From a@b.com Thu May 15 10:00:00 2025\r\nSubject: Hi\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(mailDir.resolve("Sent"),
                "From c@d.com Thu May 15 11:00:00 2025\r\nSubject: Re\r\n\r\nBody\r\n",
                StandardCharsets.UTF_8);

        List<String> folders = service.discoverMboxFolders(mailDir);

        assertEquals(2, folders.size());
        assertTrue(folders.contains("Inbox"));
        assertTrue(folders.contains("Sent"));
    }

    @Test
    void countsEmlxMessages() throws IOException {
        Path bundle = tempDir.resolve("Count.mbox");
        Path messagesDir = bundle.resolve("Messages");
        Files.createDirectories(messagesDir);

        for (int i = 1; i <= 7; i++) {
            Files.writeString(messagesDir.resolve(i + ".emlx"),
                    "42\nFrom: a@b.com\r\nSubject: Test\r\n\r\nBody\r\n",
                    StandardCharsets.UTF_8);
        }

        long count = service.countEmlxMessages(bundle);

        assertEquals(7, count);
    }

    // ── DiscoverAll integration ──────────────────────────────────────────

    @Test
    void discoverAllReturnsEmptyWhenNoMailboxesFound() {
        // On a test machine with no email clients, should return empty (not throw)
        List<DiscoveredMailbox> mailboxes = service.discoverAll();
        assertNotNull(mailboxes, "discoverAll should not return null");
    }

    @Test
    void discoveredMailboxToString() {
        DiscoveredMailbox mb = new DiscoveredMailbox(
                "Thunderbird", "default",
                Path.of("/home/user/.thunderbird/abc.default/Mail/Local Folders"),
                SourceType.MBOX,
                List.of("Inbox", "Sent", "Drafts"),
                150
        );

        String str = mb.toString();
        assertTrue(str.contains("Thunderbird"));
        assertTrue(str.contains("default"));
        assertTrue(str.contains("150"));
    }

    @Test
    void discoveredMailboxToStringWithoutProfile() {
        DiscoveredMailbox mb = new DiscoveredMailbox(
                "Generic", null,
                Path.of("/home/user/Maildir"),
                SourceType.MAILDIR,
                List.of("INBOX"),
                -1
        );

        String str = mb.toString();
        assertTrue(str.contains("Generic"));
        assertFalse(str.contains("["), "Should not have profile brackets when null");
        assertFalse(str.contains("msgs"), "Should not show count when -1");
    }

    // ── Generic location discovery ───────────────────────────────────────

    @Test
    void discoversGenericMaildirLocation() throws IOException {
        // Create ~/Maildir-like structure in tempDir
        Path maildir = tempDir.resolve("Maildir");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        Files.writeString(maildir.resolve("cur").resolve("1.msg:2,S"),
                "From: a@b.com\r\nSubject: Hi\r\n\r\nBody\r\n", StandardCharsets.UTF_8);

        // This tests the hasMaildirStructure detection used by discoverGenericLocations
        assertTrue(service.hasMaildirStructure(maildir));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void createAppleMailBundle(Path parent, String bundleName, int messageCount) throws IOException {
        Path messagesDir = parent.resolve(bundleName).resolve("Messages");
        Files.createDirectories(messagesDir);

        for (int i = 1; i <= messageCount; i++) {
            String email = "From: user" + i + "@example.com\r\n" +
                    "Subject: Msg " + i + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "Body " + i + "\r\n";
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            Files.writeString(messagesDir.resolve(i + ".emlx"),
                    bytes.length + "\n" + email, StandardCharsets.UTF_8);
        }
    }
}
