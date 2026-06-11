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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EmailInboxCrawlerTest {

    private EmailInboxCrawler crawler;

    @TempDir
    Path tempDir;

    private static final String EML_TEMPLATE =
            "From: user%d@test.com\r\n" +
            "To: inbox@test.com\r\n" +
            "Subject: Message %d\r\n" +
            "Message-ID: <msg%03d@test.com>\r\n" +
            "Date: Thu, 15 May 2025 %02d:00:00 +0000\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Body of message %d.\r\n";

    @BeforeEach
    void setUp() {
        crawler = new EmailInboxCrawler();
    }

    // ── Identity and capability ───────────────────────────────────────────

    @Test
    void crawlerIdAndSourceTypes() {
        assertEquals("email-inbox", crawler.getId());
        assertTrue(crawler.getSupportedSourceTypes().contains(SourceType.MAILDIR));
        assertTrue(crawler.getSupportedSourceTypes().contains(SourceType.MBOX));
        assertTrue(crawler.getSupportedSourceTypes().contains(SourceType.PST));
        assertTrue(crawler.getSupportedSourceTypes().contains(SourceType.EMLX_DIR));
        assertEquals("Email Inbox Crawler", crawler.getName());
        assertNotNull(crawler.getDescription());
    }

    // ── Validation ────────────────────────────────────────────────────────

    @Test
    void validationPassesForExistingDirectory() throws IOException {
        Path maildir = createMaildir(tempDir, "valid");
        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();
        assertTrue(crawler.validate(config).isEmpty());
    }

    @Test
    void validationPassesForExistingMboxFile() throws IOException {
        Path mbox = createMboxFile(tempDir, "valid.mbox", 1);
        CrawlConfig config = CrawlConfig.builder()
                .seed(mbox.toString())
                .build();
        assertTrue(crawler.validate(config).isEmpty());
    }

    @Test
    void validationFailsForMissingPath() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("/nonexistent/path")
                .build();
        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not exist")));
    }

    @Test
    void validationFailsForBlankSeed() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("")
                .build();
        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
    }

    // ── Maildir crawling ──────────────────────────────────────────────────

    @Test
    void crawlsMaildirAndDiscoverMessages() throws Exception {
        Path maildir = createMaildir(tempDir, "CrawlMaildir");
        for (int i = 0; i < 5; i++) {
            writeEml(maildir.resolve("cur"), i + ".M" + i + ":2,S", i);
        }
        for (int i = 5; i < 8; i++) {
            writeEml(maildir.resolve("new"), i + ".M" + i, i);
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(8, result.discoveredItems.size(), "Should discover 8 emails");
        assertEquals(CrawlStatus.COMPLETED, result.summary.status());
        assertEquals(8, result.summary.totalDiscovered());
        assertEquals(8, result.summary.totalProcessed());
        assertEquals(0, result.summary.totalFailed());
    }

    @Test
    void crawlsMaildirWithSubfolders() throws Exception {
        Path root = tempDir.resolve("MultiFolder");
        Files.createDirectories(root);

        createMaildir(root, "");
        writeEml(root.resolve("cur"), "1.M1:2,S", 1);

        Path sentDir = createMaildir(root, ".Sent");
        writeEml(sentDir.resolve("cur"), "2.M2:2,S", 2);

        Path draftsDir = createMaildir(root, ".Drafts");
        writeEml(draftsDir.resolve("new"), "3.M3", 3);

        CrawlConfig config = CrawlConfig.builder()
                .seed(root.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertTrue(result.discoveredItems.size() >= 3,
                "Should discover messages across all subfolders, got " + result.discoveredItems.size());
    }

    @Test
    void crawlsMaildirWithFolderFilter() throws Exception {
        Path root = tempDir.resolve("FilteredCrawl");
        Files.createDirectories(root);

        createMaildir(root, "");
        writeEml(root.resolve("cur"), "1.M1:2,S", 1);

        Path sentDir = createMaildir(root, ".Sent");
        writeEml(sentDir.resolve("cur"), "2.M2:2,S", 2);

        Path trashDir = createMaildir(root, ".Trash");
        writeEml(trashDir.resolve("cur"), "3.M3:2,ST", 3);

        CrawlConfig config = CrawlConfig.builder()
                .seed(root.toString())
                .properties(Map.of("folders", "Sent"))
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discoveredItems.size(), "Should only crawl Sent folder");
    }

    @Test
    void crawlItemsHaveCorrectSourceDescriptor() throws Exception {
        Path maildir = createMaildir(tempDir, "DescriptorTest");
        writeEml(maildir.resolve("cur"), "1234.M1:2,S", 1);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .collectionName("test-collection")
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discoveredItems.size());
        CrawlItem item = result.discoveredItems.get(0);

        assertNotNull(item.getSourceDescriptor());
        assertEquals(SourceType.MAILDIR, item.getSourceDescriptor().getType());
        assertNotNull(item.getSourceDescriptor().getPathOrUrl());
        assertEquals("test-collection", item.getSourceDescriptor().getCollectionName());
        assertEquals("message/rfc822", item.getContentType());
    }

    // ── mbox crawling ─────────────────────────────────────────────────────

    @Test
    void crawlsMboxFile() throws Exception {
        Path mbox = createMboxFile(tempDir, "crawl.mbox", 5);

        CrawlConfig config = CrawlConfig.builder()
                .seed(mbox.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(5, result.discoveredItems.size(), "Should discover 5 messages in mbox");
        assertEquals(CrawlStatus.COMPLETED, result.summary.status());

        // Each item should reference the mbox file
        for (CrawlItem item : result.discoveredItems) {
            assertEquals(SourceType.MBOX, item.getSourceDescriptor().getType());
            assertTrue(item.getUrl().contains("crawl.mbox#"));
        }
    }

    // ── MaxDocuments ──────────────────────────────────────────────────────

    @Test
    void respectsMaxDocumentsLimit() throws Exception {
        Path maildir = createMaildir(tempDir, "LimitTest");
        for (int i = 0; i < 20; i++) {
            writeEml(maildir.resolve("cur"), i + ".M" + i + ":2,S", i);
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .maxDocuments(5)
                .build();

        CrawlResult result = runCrawl(config);

        assertTrue(result.discoveredItems.size() <= 5,
                "Should stop at maxDocuments, got " + result.discoveredItems.size());
    }

    // ── Incremental crawl ─────────────────────────────────────────────────

    @Test
    void incrementalCrawlSkipsUnmodifiedFiles() throws Exception {
        Path maildir = createMaildir(tempDir, "IncrMaildir");
        writeEml(maildir.resolve("cur"), "1.M1:2,S", 1);
        writeEml(maildir.resolve("cur"), "2.M2:2,S", 2);

        // First crawl
        CrawlConfig config1 = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();
        CrawlResult result1 = runCrawl(config1);
        assertEquals(2, result1.discoveredItems.size());

        CrawlState state = result1.summary.finalState();
        assertNotNull(state, "First crawl should produce state");
        assertFalse(state.getVisitedUrls().isEmpty());

        // Add a new message
        writeEml(maildir.resolve("cur"), "3.M3:2,S", 3);

        // Second crawl with previous state
        CrawlConfig config2 = CrawlConfig.builder()
                .seed(maildir.toString())
                .previousState(state)
                .build();
        CrawlResult result2 = runCrawl(config2);

        // Should discover 1 new + skip 2 existing
        assertEquals(1, result2.discoveredItems.size(),
                "Incremental crawl should only find new messages");
        assertTrue(result2.skippedCount > 0, "Should report skipped items");
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Test
    void cancelStopsCrawl() throws Exception {
        Path maildir = createMaildir(tempDir, "CancelTest");
        for (int i = 0; i < 100; i++) {
            writeEml(maildir.resolve("cur"), i + ".M" + i + ":2,S", i);
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();

        CountDownLatch firstItemLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<CrawlSummary> summaries = new CopyOnWriteArrayList<>();

        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                firstItemLatch.countDown();
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                summaries.add(summary);
                completionLatch.countDown();
            }
        };

        CrawlJob job = crawler.start(config, listener);

        // Wait for first item, then cancel
        assertTrue(firstItemLatch.await(10, TimeUnit.SECONDS));
        job.cancel();

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
        assertEquals(CrawlStatus.CANCELLED, summaries.get(0).status());
    }

    // ── Pause / Resume ────────────────────────────────────────────────────

    @Test
    void pauseAndResumeCrawl() throws Exception {
        Path maildir = createMaildir(tempDir, "PauseTest");
        for (int i = 0; i < 20; i++) {
            writeEml(maildir.resolve("cur"), i + ".M" + i + ":2,S", i);
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();

        CountDownLatch firstItemLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<CrawlItem> items = new CopyOnWriteArrayList<>();

        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                items.add(item);
                firstItemLatch.countDown();
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                completionLatch.countDown();
            }
        };

        CrawlJob job = crawler.start(config, listener);
        assertTrue(firstItemLatch.await(10, TimeUnit.SECONDS));

        job.pause();
        assertEquals(CrawlStatus.PAUSED, job.getStatus());

        // Let it sit paused briefly
        Thread.sleep(100);

        job.resume();
        assertEquals(CrawlStatus.RUNNING, job.getStatus());

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
        assertEquals(20, items.size(), "All messages should be processed after resume");
    }

    // ── Checkpoint state ──────────────────────────────────────────────────

    @Test
    void checkpointCapturesState() throws Exception {
        Path maildir = createMaildir(tempDir, "CheckpointTest");
        writeEml(maildir.resolve("cur"), "1.M1:2,S", 1);
        writeEml(maildir.resolve("cur"), "2.M2:2,S", 2);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        CrawlState state = result.summary.finalState();
        assertNotNull(state);
        assertNotNull(state.getTimestamp());
        assertEquals(2, state.getVisitedUrls().size());
        assertEquals(2, state.getLastModifiedTimes().size());

        // All visited URLs should be file paths
        for (String url : state.getVisitedUrls()) {
            assertTrue(url.contains("CheckpointTest"), "Visited URL should contain dir name: " + url);
        }
    }

    // ── Attachment extraction during crawl ──────────────────────────────

    private static final String EML_WITH_ATTACHMENT =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: With Attachment\r\n" +
            "Message-ID: <attach-msg@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"attach-boundary\"\r\n" +
            "\r\n" +
            "--attach-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "See the attached file.\r\n" +
            "--attach-boundary\r\n" +
            "Content-Type: text/plain; name=\"notes.txt\"\r\n" +
            "Content-Disposition: attachment; filename=\"notes.txt\"\r\n" +
            "\r\n" +
            "Important notes content.\r\n" +
            "--attach-boundary--\r\n";

    @Test
    void crawlMaildirEmitsAttachmentCrawlItems() throws Exception {
        Path maildir = createMaildir(tempDir, "AttachMaildir");
        Files.writeString(maildir.resolve("cur").resolve("1.M1:2,S"),
                EML_WITH_ATTACHMENT, StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .properties(Map.of("extractAttachments", "true"))
                .build();

        CrawlResult result = runCrawl(config);

        // Should have 1 email + 1 attachment = 2 discovered items
        assertEquals(2, result.discoveredItems.size(),
                "Should discover email + attachment, got: " + result.discoveredItems.size());

        // Verify the email CrawlItem
        CrawlItem emailItem = result.discoveredItems.stream()
                .filter(i -> "message/rfc822".equals(i.getContentType()))
                .findFirst().orElse(null);
        assertNotNull(emailItem, "Should have an email CrawlItem");
        assertEquals(SourceType.MAILDIR, emailItem.getSourceDescriptor().getType());

        // Verify the attachment CrawlItem
        CrawlItem attachItem = result.discoveredItems.stream()
                .filter(i -> !"message/rfc822".equals(i.getContentType()))
                .findFirst().orElse(null);
        assertNotNull(attachItem, "Should have an attachment CrawlItem");
        assertEquals("text/plain", attachItem.getContentType());
        assertEquals(SourceType.FILE, attachItem.getSourceDescriptor().getType());
        assertEquals("notes.txt", attachItem.getSourceDescriptor().getOriginalFileName());
        assertTrue(attachItem.getUrl().contains("#attachment:notes.txt"));
        assertTrue(attachItem.getParentUrl().contains("1.M1:2,S"));
    }

    @Test
    void crawlMboxEmitsAttachmentCrawlItems() throws Exception {
        StringBuilder mbox = new StringBuilder();
        mbox.append("From sender@example.com Thu May 15 10:00:00 2025\r\n");
        mbox.append(EML_WITH_ATTACHMENT);
        mbox.append("\r\n");

        Path mboxFile = tempDir.resolve("attach.mbox");
        Files.writeString(mboxFile, mbox.toString(), StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(mboxFile.toString())
                .properties(Map.of("extractAttachments", "true"))
                .build();

        CrawlResult result = runCrawl(config);

        // Should have 1 email + 1 attachment
        assertEquals(2, result.discoveredItems.size(),
                "Should discover mbox email + attachment");

        CrawlItem attachItem = result.discoveredItems.stream()
                .filter(i -> "text/plain".equals(i.getContentType()) &&
                        i.getUrl().contains("#attachment:"))
                .findFirst().orElse(null);
        assertNotNull(attachItem, "Should have attachment CrawlItem from mbox");
    }

    @Test
    void crawlWithExtractAttachmentsDisabledSkipsAttachments() throws Exception {
        Path maildir = createMaildir(tempDir, "NoAttachMaildir");
        Files.writeString(maildir.resolve("cur").resolve("1.M1:2,S"),
                EML_WITH_ATTACHMENT, StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .properties(Map.of("extractAttachments", "false"))
                .build();

        CrawlResult result = runCrawl(config);

        // Should only have the email, no attachment items
        assertEquals(1, result.discoveredItems.size(),
                "Should only have email CrawlItem when extractAttachments=false");
        assertEquals("message/rfc822", result.discoveredItems.get(0).getContentType());
    }

    @Test
    void attachmentCrawlItemLinksBackToParentEmail() throws Exception {
        Path maildir = createMaildir(tempDir, "LinkMaildir");
        Files.writeString(maildir.resolve("cur").resolve("1.M1:2,S"),
                EML_WITH_ATTACHMENT, StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .collectionName("my-collection")
                .build();

        CrawlResult result = runCrawl(config);

        CrawlItem attachItem = result.discoveredItems.stream()
                .filter(i -> i.getUrl().contains("#attachment:"))
                .findFirst().orElse(null);
        assertNotNull(attachItem);

        // Attachment should be in same collection as parent
        assertEquals("my-collection", attachItem.getSourceDescriptor().getCollectionName());

        // Metadata should link back to parent email
        Map<String, Object> meta = attachItem.getMetadata();
        assertNotNull(meta);
        assertTrue((Boolean) meta.get("email.isAttachment"));
        assertEquals("<attach-msg@example.com>", meta.get("email.parentMessageId"));
        assertEquals("With Attachment", meta.get("email.parentSubject"));
    }

    // ── EMLX crawling ──────────────────────────────────────────────────

    @Test
    void crawlsEmlxDirectory() throws Exception {
        // Create Apple Mail directory with .mbox bundles
        Path accountDir = tempDir.resolve("AppleMailCrawl");
        Path inboxMessages = accountDir.resolve("INBOX.mbox").resolve("Messages");
        Files.createDirectories(inboxMessages);

        for (int i = 1; i <= 3; i++) {
            String email = String.format(EML_TEMPLATE, i, i, i, i % 24, i);
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            Files.writeString(inboxMessages.resolve(i + ".emlx"),
                    bytes.length + "\n" + email, StandardCharsets.UTF_8);
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(accountDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(3, result.discoveredItems.size(), "Should discover 3 EMLX messages");

        for (CrawlItem item : result.discoveredItems) {
            assertEquals(SourceType.EMLX_DIR, item.getSourceDescriptor().getType());
            assertEquals("message/rfc822", item.getContentType());
            assertNotNull(item.getMetadata().get("folder"));
        }
    }

    @Test
    void crawlsEmlxWithMultipleBundles() throws Exception {
        Path accountDir = tempDir.resolve("MultiBundleCrawl");

        // INBOX with 2 messages
        Path inboxMsgs = accountDir.resolve("INBOX.mbox").resolve("Messages");
        Files.createDirectories(inboxMsgs);
        for (int i = 1; i <= 2; i++) {
            String email = String.format(EML_TEMPLATE, i, i, i, i, i);
            byte[] bytes = email.getBytes(StandardCharsets.UTF_8);
            Files.writeString(inboxMsgs.resolve(i + ".emlx"),
                    bytes.length + "\n" + email, StandardCharsets.UTF_8);
        }

        // Sent with 1 message
        Path sentMsgs = accountDir.resolve("Sent.mbox").resolve("Messages");
        Files.createDirectories(sentMsgs);
        String sentEmail = String.format(EML_TEMPLATE, 3, 3, 3, 3, 3);
        byte[] sentBytes = sentEmail.getBytes(StandardCharsets.UTF_8);
        Files.writeString(sentMsgs.resolve("1.emlx"),
                sentBytes.length + "\n" + sentEmail, StandardCharsets.UTF_8);

        CrawlConfig config = CrawlConfig.builder()
                .seed(accountDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(3, result.discoveredItems.size(), "Should discover from both bundles");
    }

    // ── Discovery mode ──────────────────────────────────────────────────

    @Test
    void validationPassesForDiscoverMode() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("") // empty seed — discovery mode doesn't need it
                .properties(Map.of("discover", "true"))
                .build();
        assertTrue(crawler.validate(config).isEmpty(),
                "Validation should pass in discover mode without valid seed path");
    }

    // ── Empty directory ───────────────────────────────────────────────────

    @Test
    void crawlsEmptyMaildirWithoutError() throws Exception {
        Path maildir = createMaildir(tempDir, "EmptyMaildir");

        CrawlConfig config = CrawlConfig.builder()
                .seed(maildir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(0, result.discoveredItems.size());
        assertEquals(CrawlStatus.COMPLETED, result.summary.status());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Path createMaildir(Path parent, String name) throws IOException {
        Path maildir = name.isEmpty() ? parent : parent.resolve(name);
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        return maildir;
    }

    private void writeEml(Path dir, String filename, int index) throws IOException {
        String content = String.format(EML_TEMPLATE, index, index, index, index % 24, index);
        Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
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

    /**
     * Runs a crawl to completion and collects all events.
     */
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
