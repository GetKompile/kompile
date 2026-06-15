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

import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EmailInboxCrawlerEdgeCaseTest {

    private EmailInboxCrawler crawler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crawler = new EmailInboxCrawler();
    }

    // ── stripMboxFromLine edge cases ─────────────────────────────────────

    @Test
    void stripMboxFromLineReturnsNullForNull() {
        assertNull(EmailInboxCrawler.stripMboxFromLine(null));
    }

    @Test
    void stripMboxFromLineStripsLeadingLF() {
        String result = EmailInboxCrawler.stripMboxFromLine("\nFrom: alice@test.com\r\nBody\r\n");
        assertTrue(result.startsWith("From:"));
    }

    @Test
    void stripMboxFromLineStripsLeadingCRLF() {
        String result = EmailInboxCrawler.stripMboxFromLine("\r\nFrom: alice@test.com\r\nBody\r\n");
        assertTrue(result.startsWith("From:"));
    }

    @Test
    void stripMboxFromLineStripsMultipleLeadingNewlines() {
        String result = EmailInboxCrawler.stripMboxFromLine("\n\r\n\nFrom: alice@test.com\r\nBody\r\n");
        assertTrue(result.startsWith("From:"), "Should strip all leading newlines: " + result);
    }

    @Test
    void stripMboxFromLineStripsFromEnvelopeLineWithLF() {
        String input = "From sender@example.com Thu May 15 10:00:00 2025\nFrom: alice@test.com\nBody\n";
        String result = EmailInboxCrawler.stripMboxFromLine(input);
        assertTrue(result.startsWith("From: alice"), "Should strip From envelope line: " + result);
    }

    @Test
    void stripMboxFromLineStripsFromEnvelopeLineWithCRLF() {
        String input = "From sender@example.com Thu May 15 10:00:00 2025\r\nFrom: alice@test.com\r\nBody\r\n";
        String result = EmailInboxCrawler.stripMboxFromLine(input);
        assertTrue(result.startsWith("From: alice"), "Should strip From envelope line (CRLF): " + result);
    }

    @Test
    void stripMboxFromLineHandlesCRLFBeforeFromEnvelope() {
        String input = "\r\n\nFrom sender@example.com Thu May 15 10:00:00 2025\r\nFrom: alice@test.com\r\n";
        String result = EmailInboxCrawler.stripMboxFromLine(input);
        assertTrue(result.startsWith("From: alice"), "Should strip leading whitespace + From line: " + result);
    }

    @Test
    void stripMboxFromLineLeavesNormalHeadersUntouched() {
        String input = "From: alice@test.com\r\nTo: bob@test.com\r\n\r\nBody\r\n";
        String result = EmailInboxCrawler.stripMboxFromLine(input);
        assertEquals(input, result, "Should not modify messages without From envelope line");
    }

    @Test
    void stripMboxFromLineHandlesEmptyString() {
        assertEquals("", EmailInboxCrawler.stripMboxFromLine(""));
    }

    @Test
    void stripMboxFromLineHandlesOnlyNewlines() {
        assertEquals("", EmailInboxCrawler.stripMboxFromLine("\n\r\n\n"));
    }

    @Test
    void stripMboxFromLineHandlesFromEnvelopeWithNoFollowingContent() {
        String input = "From sender@example.com Thu May 15 10:00:00 2025\n";
        String result = EmailInboxCrawler.stripMboxFromLine(input);
        assertEquals("", result, "Should strip From line leaving empty string");
    }

    // ── Nested multipart attachment ──────────────────────────────────────

    private static final String EMAIL_NESTED_MULTIPART =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Nested Multipart\r\n" +
            "Message-ID: <nested@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"outer\"\r\n" +
            "\r\n" +
            "--outer\r\n" +
            "Content-Type: multipart/alternative; boundary=\"inner\"\r\n" +
            "\r\n" +
            "--inner\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Plain text body.\r\n" +
            "--inner\r\n" +
            "Content-Type: multipart/related; boundary=\"related\"\r\n" +
            "\r\n" +
            "--related\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><body><p>HTML body with <img src=\"cid:image1\"/></p></body></html>\r\n" +
            "--related\r\n" +
            "Content-Type: image/png; name=\"logo.png\"\r\n" +
            "Content-ID: <image1>\r\n" +
            "Content-Disposition: inline; filename=\"logo.png\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPgPAAEDAQAIicLsAAAA\r\n" +
            "--related--\r\n" +
            "--inner--\r\n" +
            "--outer\r\n" +
            "Content-Type: application/pdf; name=\"report.pdf\"\r\n" +
            "Content-Disposition: attachment; filename=\"report.pdf\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyA+PgplbmRvYmoK\r\n" +
            "--outer--\r\n";

    @Test
    void extractsAttachmentFromDeeplyNestedMultipart() throws Exception {
        Path maildir = createMaildir(tempDir, "NestedMultipart");
        Files.writeString(maildir.resolve("cur").resolve("1.M1:2,S"),
                EMAIL_NESTED_MULTIPART, StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .properties(Map.of("extractAttachments", "true"))
                .build();

        CrawlResult result = runCrawl(config);

        // Should have email + at least the PDF attachment
        assertTrue(result.discoveredItems.size() >= 2,
                "Should discover email + attachment(s), got " + result.discoveredItems.size());

        CrawlItem pdfAttach = result.discoveredItems.stream()
                .filter(i -> i.getUrl().contains("#attachment:report.pdf"))
                .findFirst().orElse(null);
        assertNotNull(pdfAttach, "Should have PDF attachment from outer multipart/mixed");
        assertTrue(pdfAttach.getContentType().contains("application/pdf"));
        assertEquals(SourceType.FILE, pdfAttach.getSourceDescriptor().getType());
    }

    // ── Thunderbird .sbd folder hierarchy ────────────────────────────────

    @Test
    void crawlsThunderbirdSbdHierarchy() throws Exception {
        // Thunderbird stores subfolders as:
        // root/
        //   INBOX          <- mbox file
        //   INBOX.sbd/     <- directory containing sub-mboxes
        //     Important    <- mbox file
        //     Important.sbd/
        //       Urgent     <- mbox file

        Path root = tempDir.resolve("ThunderbirdProfile");
        Files.createDirectories(root);

        // Root INBOX as mbox
        createMboxFile(root, "INBOX", 2);

        // INBOX.sbd/Important as mbox
        Path inboxSbd = root.resolve("INBOX.sbd");
        Files.createDirectories(inboxSbd);
        createMboxFile(inboxSbd, "Important", 1);

        // INBOX.sbd/Important.sbd/Urgent as mbox
        Path importantSbd = inboxSbd.resolve("Important.sbd");
        Files.createDirectories(importantSbd);
        createMboxFile(importantSbd, "Urgent", 1);

        CrawlConfig config = CrawlConfig.builder()
                .seed(root.toString())
                .build();

        CrawlResult result = runCrawl(config);

        // Should discover messages from all levels: INBOX(2) + Important(1) + Urgent(1) = 4
        assertTrue(result.discoveredItems.size() >= 4,
                "Should discover from .sbd hierarchy, got " + result.discoveredItems.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final String EML_TEMPLATE =
            "From: user%d@test.com\r\n" +
            "To: inbox@test.com\r\n" +
            "Subject: Message %d\r\n" +
            "Message-ID: <msg%03d@test.com>\r\n" +
            "Date: Thu, 15 May 2025 %02d:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Body of message %d.\r\n";

    private Path createMaildir(Path parent, String name) throws IOException {
        Path maildir = name.isEmpty() ? parent : parent.resolve(name);
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        return maildir;
    }

    private Path createMboxFile(Path dir, String filename, int messageCount) throws IOException {
        StringBuilder mbox = new StringBuilder();
        for (int i = 0; i < messageCount; i++) {
            mbox.append("From sender@example.com Thu May 15 10:00:00 2025\r\n");
            mbox.append(String.format(EML_TEMPLATE, i, i, i, i % 24, i));
            mbox.append("\r\n");
        }
        Path file = dir.resolve(filename);
        Files.writeString(file, mbox.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private CrawlResult runCrawl(CrawlConfig config) throws InterruptedException {
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        List<CrawlItem> processed = new CopyOnWriteArrayList<>();
        List<CrawlSummary> summaries = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        int[] skippedCount = {0};

        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                processed.add(item);
            }

            @Override
            public void onDocumentSkipped(String url, String reason) {
                skippedCount[0]++;
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                summaries.add(summary);
                completionLatch.countDown();
            }
        };

        CrawlJob job = crawler.start(config, listener);
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "Crawl did not complete within timeout");
        assertFalse(summaries.isEmpty(), "Should have a summary");

        CrawlResult result = new CrawlResult();
        result.discoveredItems = discovered;
        result.processedItems = processed;
        result.summary = summaries.get(0);
        result.skippedCount = skippedCount[0];
        return result;
    }

    private static class CrawlResult {
        List<CrawlItem> discoveredItems;
        List<CrawlItem> processedItems;
        CrawlSummary summary;
        int skippedCount;
    }
}
