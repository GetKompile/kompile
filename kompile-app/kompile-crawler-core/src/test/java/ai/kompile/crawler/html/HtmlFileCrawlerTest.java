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

package ai.kompile.crawler.html;

import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HtmlFileCrawlerTest {

    private HtmlFileCrawler crawler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crawler = new HtmlFileCrawler();
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    void crawlerIdIsHtmlFile() {
        assertEquals("html-file", crawler.getId());
    }

    @Test
    void crawlerNameDescriptive() {
        assertNotNull(crawler.getName());
        assertFalse(crawler.getName().isEmpty());
    }

    @Test
    void supportsFileAndDirectorySourceTypes() {
        assertTrue(crawler.supports(SourceType.FILE));
        assertTrue(crawler.supports(SourceType.DIRECTORY));
        assertFalse(crawler.supports(SourceType.URL));
        assertFalse(crawler.supports(SourceType.EMAIL));
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    void validatesNonexistentPath() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("/nonexistent/path/to/html")
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not exist")));
    }

    @Test
    void validatesNonHtmlFile() throws IOException {
        Path txtFile = tempDir.resolve("readme.txt");
        Files.writeString(txtFile, "Not an HTML file");

        CrawlConfig config = CrawlConfig.builder()
                .seed(txtFile.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("not an HTML file or directory")));
    }

    @Test
    void validatesDirectorySucceeds() {
        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validatesHtmlFileSucceeds() throws IOException {
        Path htmlFile = tempDir.resolve("index.html");
        Files.writeString(htmlFile, "<html><body>Hello</body></html>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(htmlFile.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty());
    }

    // ── Single file crawl ───────────────────────────────────────────────────

    @Test
    void crawlsSingleHtmlFile() throws Exception {
        Path htmlFile = tempDir.resolve("page.html");
        Files.writeString(htmlFile, "<html><head><title>Test Page</title></head><body><p>Content</p></body></html>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(htmlFile.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discoveredItems.size());

        CrawlItem item = result.discoveredItems.get(0);
        assertEquals(htmlFile.toAbsolutePath().toString(), item.getUrl());
        assertEquals("text/html", item.getContentType());
        assertEquals(SourceType.FILE, item.getSourceDescriptor().getType());
        assertEquals("page.html", item.getSourceDescriptor().getOriginalFileName());
    }

    // ── Directory crawl ─────────────────────────────────────────────────────

    @Test
    void crawlsDirectoryOfHtmlFiles() throws Exception {
        createHtmlFile(tempDir, "index.html", "Home Page", "<p>Welcome</p>");
        createHtmlFile(tempDir, "about.html", "About", "<p>About us</p>");
        createHtmlFile(tempDir, "contact.htm", "Contact", "<p>Contact info</p>");
        Files.writeString(tempDir.resolve("readme.txt"), "Not HTML");
        Files.writeString(tempDir.resolve("style.css"), "body { color: red; }");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(3, result.discoveredItems.size(), "Should only discover HTML files");

        Set<String> filenames = new HashSet<>();
        for (CrawlItem item : result.discoveredItems) {
            filenames.add(item.getSourceDescriptor().getOriginalFileName());
        }
        assertTrue(filenames.contains("index.html"));
        assertTrue(filenames.contains("about.html"));
        assertTrue(filenames.contains("contact.htm"));
    }

    // ── Nested directory crawl ──────────────────────────────────────────────

    @Test
    void crawlsNestedDirectories() throws Exception {
        createHtmlFile(tempDir, "index.html", "Root", "<p>Root</p>");

        Path sub = tempDir.resolve("docs");
        Files.createDirectories(sub);
        createHtmlFile(sub, "guide.html", "Guide", "<p>Guide</p>");

        Path deep = sub.resolve("api");
        Files.createDirectories(deep);
        createHtmlFile(deep, "reference.xhtml", "API Ref", "<p>API</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(3, result.discoveredItems.size());
    }

    // ── Max depth ───────────────────────────────────────────────────────────

    @Test
    void respectsMaxDepth() throws Exception {
        createHtmlFile(tempDir, "root.html", "Root", "<p>Root</p>");

        Path sub = tempDir.resolve("sub");
        Files.createDirectories(sub);
        createHtmlFile(sub, "level1.html", "L1", "<p>L1</p>");

        Path deep = sub.resolve("deep");
        Files.createDirectories(deep);
        createHtmlFile(deep, "level2.html", "L2", "<p>L2</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .maxDepth(1)
                .build();

        CrawlResult result = runCrawl(config);
        // maxDepth=1 means root + 1 level of subdirectories
        assertTrue(result.discoveredItems.size() <= 2,
                "Should not descend beyond depth 1, got " + result.discoveredItems.size());
    }

    // ── Max documents ───────────────────────────────────────────────────────

    @Test
    void respectsMaxDocuments() throws Exception {
        for (int i = 0; i < 10; i++) {
            createHtmlFile(tempDir, "page" + i + ".html", "Page " + i, "<p>Content " + i + "</p>");
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .maxDocuments(3)
                .build();

        CrawlResult result = runCrawl(config);
        assertTrue(result.discoveredItems.size() <= 3,
                "Should stop after maxDocuments, got " + result.discoveredItems.size());
    }

    // ── HTML metadata extraction ────────────────────────────────────────────

    @Test
    void extractsHtmlMetadata() throws Exception {
        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"description\" content=\"A test page for metadata extraction\">\n" +
                "  <meta name=\"author\" content=\"Test Author\">\n" +
                "  <meta name=\"keywords\" content=\"test, html, crawler\">\n" +
                "  <title>Metadata Test</title>\n" +
                "</head>\n" +
                "<body><p>Body content</p></body>\n" +
                "</html>";
        Path htmlFile = tempDir.resolve("meta.html");
        Files.writeString(htmlFile, html);

        CrawlConfig config = CrawlConfig.builder()
                .seed(htmlFile.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discoveredItems.size());

        Map<String, Object> meta = result.discoveredItems.get(0).getMetadata();
        assertEquals("Metadata Test", meta.get("html.title"));
        assertEquals("A test page for metadata extraction", meta.get("html.description"));
        assertEquals("Test Author", meta.get("html.author"));
        assertEquals("test, html, crawler", meta.get("html.keywords"));
        assertEquals("en", meta.get("html.language"));
        assertEquals("UTF-8", meta.get("html.charset"));
    }

    @Test
    void metadataExtractionCanBeDisabled() throws Exception {
        String html = "<html><head><title>Should Not Extract</title></head><body>Hi</body></html>";
        Path htmlFile = tempDir.resolve("nometa.html");
        Files.writeString(htmlFile, html);

        CrawlConfig config = CrawlConfig.builder()
                .seed(htmlFile.toString())
                .properties(Map.of("extractMetadata", "false"))
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discoveredItems.size());

        Map<String, Object> meta = result.discoveredItems.get(0).getMetadata();
        assertNull(meta.get("html.title"));
    }

    // ── Link extraction ─────────────────────────────────────────────────────

    @Test
    void extractsLocalLinks() throws Exception {
        createHtmlFile(tempDir, "about.html", "About", "<p>About page</p>");

        String indexHtml = "<html><body>" +
                "<a href=\"about.html\">About</a>" +
                "<a href=\"https://example.com\">External</a>" +
                "<a href=\"mailto:test@test.com\">Email</a>" +
                "<a href=\"#section\">Anchor</a>" +
                "</body></html>";
        Path indexFile = tempDir.resolve("index.html");
        Files.writeString(indexFile, indexHtml);

        List<String> links = crawler.extractLocalLinks(indexHtml, indexFile, tempDir);
        assertEquals(1, links.size(), "Should only extract the local HTML link");
        assertTrue(links.get(0).endsWith("about.html"));
    }

    @Test
    void linkExtractionStripsFragments() throws Exception {
        createHtmlFile(tempDir, "page.html", "Page", "<p>Page</p>");

        String html = "<html><body><a href=\"page.html#section2\">Link</a></body></html>";
        Path source = tempDir.resolve("source.html");
        Files.writeString(source, html);

        List<String> links = crawler.extractLocalLinks(html, source, tempDir);
        assertEquals(1, links.size());
        assertFalse(links.get(0).contains("#"));
    }

    @Test
    void linkExtractionIgnoresNonHtmlTargets() throws Exception {
        Files.writeString(tempDir.resolve("data.json"), "{}");

        String html = "<html><body><a href=\"data.json\">Data</a></body></html>";
        Path source = tempDir.resolve("index.html");
        Files.writeString(source, html);

        List<String> links = crawler.extractLocalLinks(html, source, tempDir);
        assertTrue(links.isEmpty(), "Should not include non-HTML links");
    }

    @Test
    void linkExtractionRespectsRootBoundary() throws Exception {
        // Create a file outside the root
        Path outside = tempDir.getParent().resolve("outside.html");
        // Don't actually create it — just reference it

        String html = "<html><body><a href=\"../outside.html\">Escape</a></body></html>";
        Path source = tempDir.resolve("index.html");
        Files.writeString(source, html);

        List<String> links = crawler.extractLocalLinks(html, source, tempDir);
        assertTrue(links.isEmpty(), "Should not include links outside root directory");
    }

    // ── Follow links ────────────────────────────────────────────────────────

    @Test
    void followLinksDiscoversLinkedFiles() throws Exception {
        // Create a file only reachable via link (in a sibling dir the walk already covers)
        Path sub = tempDir.resolve("pages");
        Files.createDirectories(sub);
        createHtmlFile(sub, "linked.html", "Linked", "<p>Discovered via link</p>");

        // Index links to pages/linked.html
        String indexHtml = "<html><body><a href=\"pages/linked.html\">Go</a></body></html>";
        Files.writeString(tempDir.resolve("index.html"), indexHtml);

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .properties(Map.of("followLinks", "true"))
                .build();

        CrawlResult result = runCrawl(config);
        // Both index.html and pages/linked.html should be discovered
        assertTrue(result.discoveredItems.size() >= 2);
    }

    // ── Include/exclude patterns ────────────────────────────────────────────

    @Test
    void includePatternFiltersFiles() throws Exception {
        createHtmlFile(tempDir, "report-2024.html", "Report", "<p>Report</p>");
        createHtmlFile(tempDir, "report-2025.html", "Report 2", "<p>Report 2</p>");
        createHtmlFile(tempDir, "other.html", "Other", "<p>Other</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .includePatterns(List.of("report-.*"))
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(2, result.discoveredItems.size());
    }

    @Test
    void excludePatternFiltersFiles() throws Exception {
        createHtmlFile(tempDir, "page1.html", "P1", "<p>P1</p>");
        createHtmlFile(tempDir, "page2.html", "P2", "<p>P2</p>");
        createHtmlFile(tempDir, "draft.html", "Draft", "<p>Draft</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .excludePatterns(List.of("draft.*"))
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(2, result.discoveredItems.size());
        assertTrue(result.discoveredItems.stream()
                .noneMatch(i -> i.getSourceDescriptor().getOriginalFileName().contains("draft")));
    }

    // ── Hidden files ────────────────────────────────────────────────────────

    @Test
    void skipsHiddenFilesByDefault() throws Exception {
        createHtmlFile(tempDir, "visible.html", "Visible", "<p>V</p>");
        createHtmlFile(tempDir, ".hidden.html", "Hidden", "<p>H</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discoveredItems.size());
        assertEquals("visible.html", result.discoveredItems.get(0).getSourceDescriptor().getOriginalFileName());
    }

    @Test
    void includesHiddenFilesWhenConfigured() throws Exception {
        createHtmlFile(tempDir, "visible.html", "Visible", "<p>V</p>");
        createHtmlFile(tempDir, ".hidden.html", "Hidden", "<p>H</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .properties(Map.of("includeHidden", "true"))
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(2, result.discoveredItems.size());
    }

    // ── Incremental crawl ───────────────────────────────────────────────────

    @Test
    void incrementalCrawlSkipsUnchangedFiles() throws Exception {
        Path htmlFile = tempDir.resolve("page.html");
        Files.writeString(htmlFile, "<html><body>Original</body></html>");

        // First crawl
        CrawlConfig config1 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();
        CrawlResult result1 = runCrawl(config1);
        assertEquals(1, result1.discoveredItems.size());

        // Second crawl with previous state — file unchanged
        CrawlConfig config2 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .previousState(result1.summary.finalState())
                .build();
        CrawlResult result2 = runCrawl(config2);
        assertEquals(0, result2.discoveredItems.size(), "Should skip unchanged file");
    }

    @Test
    void incrementalCrawlProcessesModifiedFiles() throws Exception {
        Path htmlFile = tempDir.resolve("page.html");
        Files.writeString(htmlFile, "<html><body>Original</body></html>");

        CrawlConfig config1 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();
        CrawlResult result1 = runCrawl(config1);

        // Modify the file (need to ensure different last-modified time)
        Thread.sleep(50);
        Files.writeString(htmlFile, "<html><body>Modified content</body></html>");
        // Touch the file to ensure different mtime
        htmlFile.toFile().setLastModified(System.currentTimeMillis() + 1000);

        CrawlConfig config2 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .previousState(result1.summary.finalState())
                .build();
        CrawlResult result2 = runCrawl(config2);
        assertEquals(1, result2.discoveredItems.size(), "Should process modified file");
    }

    // ── Content hash in metadata ────────────────────────────────────────────

    @Test
    void itemMetadataContainsContentHash() throws Exception {
        createHtmlFile(tempDir, "page.html", "Test", "<p>Body</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discoveredItems.size());

        Map<String, Object> meta = result.discoveredItems.get(0).getMetadata();
        assertNotNull(meta.get("contentHash"));
        assertTrue(((String) meta.get("contentHash")).matches("[0-9a-f]{64}"), "Should be SHA-256 hex");
    }

    // ── Cancel support ──────────────────────────────────────────────────────

    @Test
    void cancelStopsCrawl() throws Exception {
        // Create many files
        for (int i = 0; i < 100; i++) {
            createHtmlFile(tempDir, "page" + i + ".html", "Page " + i, "<p>" + i + "</p>");
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch firstItem = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);

        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
                firstItem.countDown();
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                completionLatch.countDown();
            }
        };

        CrawlJob job = crawler.start(config, listener);
        assertTrue(firstItem.await(10, TimeUnit.SECONDS), "Should discover at least one item");
        job.cancel();
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Should complete after cancel");

        assertEquals(CrawlStatus.CANCELLED, job.getStatus());
        assertTrue(discovered.size() < 100, "Should not discover all files after cancel");
    }

    // ── Extension detection ─────────────────────────────────────────────────

    @Test
    void recognizesAllHtmlExtensions() throws Exception {
        createHtmlFile(tempDir, "a.html", "A", "<p>A</p>");
        createHtmlFile(tempDir, "b.htm", "B", "<p>B</p>");
        createHtmlFile(tempDir, "c.xhtml", "C", "<p>C</p>");
        createHtmlFile(tempDir, "d.shtml", "D", "<p>D</p>");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(4, result.discoveredItems.size());
    }

    // ── isHtmlFile static method ────────────────────────────────────────────

    @Test
    void isHtmlFileDetectsHtmlExtensions() {
        assertTrue(HtmlFileCrawler.isHtmlFile(Path.of("page.html")));
        assertTrue(HtmlFileCrawler.isHtmlFile(Path.of("page.htm")));
        assertTrue(HtmlFileCrawler.isHtmlFile(Path.of("page.xhtml")));
        assertTrue(HtmlFileCrawler.isHtmlFile(Path.of("page.shtml")));
        assertTrue(HtmlFileCrawler.isHtmlFile(Path.of("PAGE.HTML")));
        assertFalse(HtmlFileCrawler.isHtmlFile(Path.of("page.txt")));
        assertFalse(HtmlFileCrawler.isHtmlFile(Path.of("page.css")));
        assertFalse(HtmlFileCrawler.isHtmlFile(Path.of("page.js")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void createHtmlFile(Path dir, String filename, String title, String bodyContent) throws IOException {
        String html = "<!DOCTYPE html>\n<html><head><title>" + title +
                "</title></head><body>" + bodyContent + "</body></html>";
        Files.writeString(dir.resolve(filename), html, StandardCharsets.UTF_8);
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
