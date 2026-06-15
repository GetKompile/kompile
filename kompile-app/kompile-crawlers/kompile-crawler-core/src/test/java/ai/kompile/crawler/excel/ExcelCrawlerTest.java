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

package ai.kompile.crawler.excel;

import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExcelCrawlerTest {

    private ExcelCrawler crawler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crawler = new ExcelCrawler();
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    void crawlerIdIsExcel() {
        assertEquals("excel", crawler.getId());
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
                .seed("/nonexistent/path/to/spreadsheets")
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not exist")));
    }

    @Test
    void validatesNonSpreadsheetFile() throws IOException {
        Path txtFile = tempDir.resolve("readme.txt");
        Files.writeString(txtFile, "Not a spreadsheet");

        CrawlConfig config = CrawlConfig.builder()
                .seed(txtFile.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("not a directory or spreadsheet")));
    }

    @Test
    void validatesDirectorySucceeds() {
        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Valid directory should produce no errors: " + errors);
    }

    @Test
    void validatesSpreadsheetFileSucceeds() throws IOException {
        Path xlsxFile = tempDir.resolve("report.xlsx");
        Files.write(xlsxFile, new byte[]{0});

        CrawlConfig config = CrawlConfig.builder()
                .seed(xlsxFile.toString())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Valid xlsx file should produce no errors: " + errors);
    }

    // ── Single file crawl ───────────────────────────────────────────────────

    @Test
    void crawlsSingleXlsxFile() throws Exception {
        Path xlsxFile = tempDir.resolve("data.xlsx");
        Files.write(xlsxFile, new byte[]{0x50, 0x4B}); // PK header placeholder

        CrawlConfig config = CrawlConfig.builder()
                .seed(xlsxFile.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discovered.size(), "Should discover single xlsx file");
        CrawlItem item = result.discovered.get(0);
        assertEquals(xlsxFile.toAbsolutePath().toString(), item.getUrl());
        assertEquals("spreadsheet", item.getMetadata().get("content_type"));
        assertEquals("excel-modern", item.getMetadata().get("spreadsheet_type"));
        assertEquals("xlsx", item.getMetadata().get("file_extension"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                item.getContentType());
    }

    @Test
    void crawlsSingleCsvFile() throws Exception {
        Path csvFile = tempDir.resolve("data.csv");
        Files.writeString(csvFile, "a,b,c\n1,2,3\n");

        CrawlConfig config = CrawlConfig.builder()
                .seed(csvFile.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discovered.size());
        CrawlItem item = result.discovered.get(0);
        assertEquals("csv", item.getMetadata().get("spreadsheet_type"));
        assertEquals("text/csv", item.getContentType());
    }

    // ── Directory crawl ─────────────────────────────────────────────────────

    @Test
    void crawlsDirectoryFindingSpreadsheets() throws Exception {
        createFile("report.xlsx");
        createFile("data.xls");
        createFile("stats.csv");
        createFile("readme.txt");
        createFile("notes.pdf");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        // Should find 3 spreadsheets (xlsx, xls, csv), skip txt and pdf
        assertEquals(3, result.discovered.size(),
                "Should discover xlsx, xls, csv but not txt/pdf");

        Set<String> extensions = new HashSet<>();
        for (CrawlItem item : result.discovered) {
            extensions.add((String) item.getMetadata().get("file_extension"));
        }
        assertTrue(extensions.contains("xlsx"));
        assertTrue(extensions.contains("xls"));
        assertTrue(extensions.contains("csv"));
    }

    @Test
    void crawlsNestedDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("sub1/sub2"));
        createFile("top.xlsx");
        createFile("sub1/mid.xlsm");
        createFile("sub1/sub2/deep.ods");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(3, result.discovered.size(), "Should find spreadsheets at all levels");

        // Verify depth is tracked
        for (CrawlItem item : result.discovered) {
            int depth = item.getDepth();
            assertTrue(depth >= 0 && depth <= 3, "Depth should be reasonable: " + depth);
        }
    }

    @Test
    void respectsMaxDepth() throws Exception {
        Files.createDirectories(tempDir.resolve("a/b/c"));
        createFile("top.xlsx");
        createFile("a/level1.xlsx");
        createFile("a/b/level2.xlsx");
        createFile("a/b/c/level3.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .maxDepth(2)
                .build();

        CrawlResult result = runCrawl(config);

        // maxDepth=2 means root + 2 levels down. level3.xlsx at depth 3 should be skipped.
        assertTrue(result.discovered.size() <= 3,
                "maxDepth=2 should limit traversal depth. Found: " + result.discovered.size());
    }

    @Test
    void respectsMaxDocuments() throws Exception {
        for (int i = 0; i < 10; i++) {
            createFile("file" + i + ".xlsx");
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .maxDocuments(3)
                .build();

        CrawlResult result = runCrawl(config);

        assertTrue(result.discovered.size() <= 3,
                "maxDocuments=3 should limit results. Found: " + result.discovered.size());
    }

    // ── CSV inclusion toggle ────────────────────────────────────────────────

    @Test
    void excludesCsvWhenDisabled() throws Exception {
        createFile("data.xlsx");
        createFile("data.csv");
        createFile("data.tsv");

        Map<String, Object> properties = new HashMap<>();
        properties.put("includeCsv", false);

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .properties(properties)
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discovered.size(), "Only xlsx should be found with includeCsv=false");
        assertEquals("xlsx", result.discovered.get(0).getMetadata().get("file_extension"));
    }

    // ── Hidden files ────────────────────────────────────────────────────────

    @Test
    void skipsHiddenFilesByDefault() throws Exception {
        createFile("visible.xlsx");
        createFile(".hidden.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(1, result.discovered.size(), "Hidden files should be skipped by default");
        assertTrue(result.discovered.get(0).getUrl().contains("visible.xlsx"));
    }

    @Test
    void includesHiddenFilesWhenEnabled() throws Exception {
        createFile("visible.xlsx");
        createFile(".hidden.xlsx");

        Map<String, Object> properties = new HashMap<>();
        properties.put("includeHidden", true);

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .properties(properties)
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(2, result.discovered.size(), "Hidden files should be included when enabled");
    }

    // ── Include / exclude patterns ──────────────────────────────────────────

    @Test
    void includePatternFiltersFiles() throws Exception {
        createFile("report-q1.xlsx");
        createFile("report-q2.xlsx");
        createFile("budget.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .includePatterns(List.of("report*"))
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(2, result.discovered.size(), "Only files matching 'report*' should be included");
        for (CrawlItem item : result.discovered) {
            assertTrue(item.getUrl().contains("report"), "Should only include report files");
        }
    }

    @Test
    void excludePatternFiltersFiles() throws Exception {
        createFile("report.xlsx");
        createFile("draft-report.xlsx");
        createFile("budget.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .excludePatterns(List.of("draft*"))
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(2, result.discovered.size(), "Draft files should be excluded");
        for (CrawlItem item : result.discovered) {
            assertFalse(item.getUrl().contains("draft"), "Draft files should be excluded");
        }
    }

    // ── Content type classification ─────────────────────────────────────────

    @Test
    void classifiesSpreadsheetTypes() throws Exception {
        createFile("legacy.xls");
        createFile("modern.xlsx");
        createFile("macro.xlsm");
        createFile("calc.ods");
        createFile("data.csv");
        createFile("tabs.tsv");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        Map<String, String> extToType = new HashMap<>();
        for (CrawlItem item : result.discovered) {
            String ext = (String) item.getMetadata().get("file_extension");
            String type = (String) item.getMetadata().get("spreadsheet_type");
            extToType.put(ext, type);
        }

        assertEquals("excel-legacy", extToType.get("xls"));
        assertEquals("excel-modern", extToType.get("xlsx"));
        assertEquals("excel-modern", extToType.get("xlsm"));
        assertEquals("libreoffice-calc", extToType.get("ods"));
        assertEquals("csv", extToType.get("csv"));
        assertEquals("tsv", extToType.get("tsv"));
    }

    @Test
    void setsCorrectMimeTypes() throws Exception {
        createFile("data.xlsx");
        createFile("data.xls");
        createFile("data.csv");
        createFile("data.ods");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        Map<String, String> extToMime = new HashMap<>();
        for (CrawlItem item : result.discovered) {
            String ext = (String) item.getMetadata().get("file_extension");
            extToMime.put(ext, item.getContentType());
        }

        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                extToMime.get("xlsx"));
        assertEquals("application/vnd.ms-excel", extToMime.get("xls"));
        assertEquals("text/csv", extToMime.get("csv"));
        assertEquals("application/vnd.oasis.opendocument.spreadsheet", extToMime.get("ods"));
    }

    // ── Metadata ────────────────────────────────────────────────────────────

    @Test
    void crawlItemsHaveRequiredMetadata() throws Exception {
        createFile("data.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discovered.size());

        CrawlItem item = result.discovered.get(0);
        assertNotNull(item.getUrl());
        assertNotNull(item.getContentType());
        assertNotNull(item.getDiscoveredAt());
        assertNotNull(item.getMetadata());
        assertNotNull(item.getMetadata().get("content_type"));
        assertNotNull(item.getMetadata().get("spreadsheet_type"));
        assertNotNull(item.getMetadata().get("file_extension"));
        assertNotNull(item.getMetadata().get("relativePath"));
        assertNotNull(item.getMetadata().get("fileSize"));
        assertNotNull(item.getMetadata().get("lastModified"));

        // Source descriptor should be present for downstream loader
        assertNotNull(item.getSourceDescriptor());
        assertEquals(SourceType.FILE, item.getSourceDescriptor().getType());
    }

    @Test
    void parentUrlPointsToRoot() throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        createFile("sub/nested.xlsx");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);
        assertEquals(1, result.discovered.size());

        CrawlItem item = result.discovered.get(0);
        assertEquals(tempDir.toAbsolutePath().toString(), item.getParentUrl());
    }

    // ── Incremental crawl ───────────────────────────────────────────────────

    @Test
    void incrementalCrawlSkipsUnchangedFiles() throws Exception {
        Path file = tempDir.resolve("data.xlsx");
        Files.write(file, new byte[]{0x50, 0x4B});

        // First crawl
        CrawlConfig config1 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();
        CrawlResult result1 = runCrawl(config1);
        assertEquals(1, result1.discovered.size());

        // Capture state from the first crawl job
        CrawlState state = result1.checkpointState;
        assertNotNull(state, "Checkpoint state should be available");

        // Second crawl with previous state — file unchanged
        CrawlConfig config2 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .previousState(state)
                .build();
        CrawlResult result2 = runCrawl(config2);
        assertEquals(0, result2.discovered.size(),
                "Unchanged file should be skipped on incremental crawl");
        assertTrue(result2.skippedCount > 0, "Should report skipped count");
    }

    @Test
    void incrementalCrawlReprocessesModifiedFiles() throws Exception {
        Path file = tempDir.resolve("data.xlsx");
        Files.write(file, new byte[]{0x50, 0x4B});

        // First crawl
        CrawlConfig config1 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();
        CrawlResult result1 = runCrawl(config1);
        CrawlState state = result1.checkpointState;

        // Simulate modification by sleeping and rewriting
        Thread.sleep(50);
        Files.write(file, new byte[]{0x50, 0x4B, 0x03, 0x04});

        // Force a newer modification time
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        CrawlConfig config2 = CrawlConfig.builder()
                .seed(tempDir.toString())
                .previousState(state)
                .build();
        CrawlResult result2 = runCrawl(config2);
        assertEquals(1, result2.discovered.size(),
                "Modified file should be reprocessed");
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    @Test
    void cancelStopsCrawl() throws Exception {
        // Create many files to ensure crawl takes long enough to cancel
        for (int i = 0; i < 50; i++) {
            createFile("file" + i + ".xlsx");
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CountDownLatch firstItemLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
                firstItemLatch.countDown();
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                completionLatch.countDown();
            }
        });

        // Wait for at least one item then cancel
        assertTrue(firstItemLatch.await(10, TimeUnit.SECONDS), "Should discover at least one file");
        job.cancel();

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Job should complete after cancel");
        assertEquals(CrawlStatus.CANCELLED, job.getStatus());
    }

    // ── Progress events ─────────────────────────────────────────────────────

    @Test
    void emitsProgressEvents() throws Exception {
        // Create enough files to trigger progress (every 10 files)
        for (int i = 0; i < 15; i++) {
            createFile("file" + i + ".xlsx");
        }

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        List<CrawlProgress> progressEvents = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);

        crawler.start(config, new CrawlEventListener() {
            @Override
            public void onProgress(CrawlProgress progress) {
                progressEvents.add(progress);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                completionLatch.countDown();
            }
        });

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));

        // With 15 files, we should get at least 1 progress event (emitted every 10 files)
        assertFalse(progressEvents.isEmpty(), "Should receive at least one progress event");
    }

    // ── Empty directory ─────────────────────────────────────────────────────

    @Test
    void emptyDirectoryCompletesSuccessfully() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(0, result.discovered.size());
        assertEquals(CrawlStatus.COMPLETED, result.status);
    }

    @Test
    void directoryWithNoSpreadsheetsCompletesSuccessfully() throws Exception {
        createFile("readme.txt");
        createFile("image.png");
        createFile("document.pdf");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();

        CrawlResult result = runCrawl(config);

        assertEquals(0, result.discovered.size());
        assertEquals(CrawlStatus.COMPLETED, result.status);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void createFile(String relativePath) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{0});
    }

    private CrawlResult runCrawl(CrawlConfig config) throws InterruptedException {
        CrawlResult result = new CrawlResult();
        CountDownLatch completionLatch = new CountDownLatch(1);

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                result.discovered.add(item);
            }

            @Override
            public void onDocumentSkipped(String url, String reason) {
                result.skippedCount++;
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                result.status = summary.status();
                completionLatch.countDown();
            }
        });

        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "Crawl did not complete within timeout");

        // Capture checkpoint state for incremental tests
        result.checkpointState = ((ExcelCrawlJob) job).checkpoint();
        return result;
    }

    static class CrawlResult {
        final List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        volatile int skippedCount = 0;
        volatile CrawlStatus status;
        volatile CrawlState checkpointState;
    }
}
