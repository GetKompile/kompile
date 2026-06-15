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

package ai.kompile.crawler.fs;

import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileSystemCrawler")
class FileSystemCrawlerTest {

    private FileSystemCrawler crawler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crawler = new FileSystemCrawler();
    }

    @Test
    @DisplayName("Supports FILE and DIRECTORY source types")
    void supportsFileAndDirectorySourceTypes() {
        Set<SourceType> supported = crawler.getSupportedSourceTypes();
        assertTrue(supported.contains(SourceType.FILE));
        assertTrue(supported.contains(SourceType.DIRECTORY));
    }

    @Test
    @DisplayName("Crawler ID is 'filesystem'")
    void crawlerIdIsFilesystem() {
        assertEquals("filesystem", crawler.getId());
    }

    @Test
    @DisplayName("Validates non-existent path returns error")
    void validatesNonExistentPath() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("/nonexistent/path/xyz")
                .build();
        assertFalse(crawler.validate(config).isEmpty());
    }

    @Test
    @DisplayName("Validates existing directory passes")
    void validatesExistingDirectory() {
        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .build();
        assertTrue(crawler.validate(config).isEmpty());
    }

    @Test
    @DisplayName("Discovers files in nested subdirectories")
    void discoversFilesInNestedSubdirectories() throws Exception {
        // Create a directory tree:
        // tempDir/
        //   file1.txt
        //   sub1/
        //     file2.txt
        //     sub2/
        //       file3.txt
        Files.writeString(tempDir.resolve("file1.txt"), "content 1");
        Path sub1 = Files.createDirectories(tempDir.resolve("sub1"));
        Files.writeString(sub1.resolve("file2.txt"), "content 2");
        Path sub2 = Files.createDirectories(sub1.resolve("sub2"));
        Files.writeString(sub2.resolve("file3.txt"), "content 3");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .sourceType(SourceType.DIRECTORY)
                .maxDepth(10)
                .maxDocuments(100)
                .properties(new HashMap<>())
                .forceRecrawl(true)
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {}

            @Override
            public void onComplete(CrawlSummary summary) {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS), "Crawl should complete within 10 seconds");

        // Verify all 3 files across all levels were discovered
        assertEquals(3, discovered.size(), "Should discover files at all directory levels");

        Set<String> fileNames = new HashSet<>();
        for (CrawlItem item : discovered) {
            Path p = Path.of(item.getUrl());
            fileNames.add(p.getFileName().toString());
        }
        assertTrue(fileNames.contains("file1.txt"), "Should find file1.txt at root");
        assertTrue(fileNames.contains("file2.txt"), "Should find file2.txt in sub1");
        assertTrue(fileNames.contains("file3.txt"), "Should find file3.txt in sub1/sub2");
    }

    @Test
    @DisplayName("Respects maxDepth to limit directory descent")
    void respectsMaxDepth() throws Exception {
        // Files.walkFileTree depth: root=0, root/file=1, root/sub/file=2, root/sub/sub/file=3
        Files.writeString(tempDir.resolve("root.txt"), "root");
        Path sub1 = Files.createDirectories(tempDir.resolve("level1"));
        Files.writeString(sub1.resolve("level1.txt"), "level 1");
        Path sub2 = Files.createDirectories(sub1.resolve("level2"));
        Files.writeString(sub2.resolve("level2.txt"), "level 2");
        Path sub3 = Files.createDirectories(sub2.resolve("level3"));
        Files.writeString(sub3.resolve("level3.txt"), "level 3");

        // maxDepth=2: should get root.txt(1) and level1.txt(2), not level2.txt(3) or level3.txt(4)
        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .sourceType(SourceType.DIRECTORY)
                .maxDepth(2)
                .maxDocuments(100)
                .properties(new HashMap<>())
                .forceRecrawl(true)
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {}

            @Override
            public void onComplete(CrawlSummary summary) {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));

        Set<String> fileNames = new HashSet<>();
        for (CrawlItem item : discovered) {
            Path p = Path.of(item.getUrl());
            fileNames.add(p.getFileName().toString());
        }
        assertTrue(fileNames.contains("root.txt"), "Should find root level file (depth=1)");
        assertTrue(fileNames.contains("level1.txt"), "Should find level 1 file (depth=2)");
        assertFalse(fileNames.contains("level2.txt"), "Should NOT find level 2 file (depth=3, beyond maxDepth=2)");
        assertFalse(fileNames.contains("level3.txt"), "Should NOT find level 3 file (depth=4, beyond maxDepth=2)");
    }

    @Test
    @DisplayName("Skips hidden directories by default")
    void skipsHiddenDirectoriesByDefault() throws Exception {
        Files.writeString(tempDir.resolve("visible.txt"), "visible");
        Path hidden = Files.createDirectories(tempDir.resolve(".hidden"));
        Files.writeString(hidden.resolve("secret.txt"), "secret");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .sourceType(SourceType.DIRECTORY)
                .maxDepth(10)
                .maxDocuments(100)
                .properties(new HashMap<>())
                .forceRecrawl(true)
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {}

            @Override
            public void onComplete(CrawlSummary summary) {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(1, discovered.size(), "Should only discover visible file");
        assertTrue(discovered.get(0).getUrl().endsWith("visible.txt"));
    }

    @Test
    @DisplayName("Include patterns filter files by name/path")
    void includePatternsFilterFiles() throws Exception {
        Files.writeString(tempDir.resolve("data.csv"), "a,b,c");
        Files.writeString(tempDir.resolve("notes.txt"), "notes");
        Files.writeString(tempDir.resolve("report.pdf"), "pdf content");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .sourceType(SourceType.DIRECTORY)
                .maxDepth(10)
                .maxDocuments(100)
                .includePatterns(List.of(".*\\.csv", ".*\\.txt"))
                .properties(new HashMap<>())
                .forceRecrawl(true)
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {}

            @Override
            public void onComplete(CrawlSummary summary) {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(2, discovered.size(), "Should only discover .csv and .txt files");
        Set<String> names = new HashSet<>();
        for (CrawlItem item : discovered) {
            names.add(Path.of(item.getUrl()).getFileName().toString());
        }
        assertTrue(names.contains("data.csv"));
        assertTrue(names.contains("notes.txt"));
        assertFalse(names.contains("report.pdf"));
    }

    @Test
    @DisplayName("Source descriptor on discovered items has FILE type")
    void sourceDescriptorHasFileType() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "content");

        CrawlConfig config = CrawlConfig.builder()
                .seed(tempDir.toString())
                .sourceType(SourceType.DIRECTORY)
                .maxDepth(10)
                .maxDocuments(100)
                .properties(new HashMap<>())
                .forceRecrawl(true)
                .build();

        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {}

            @Override
            public void onComplete(CrawlSummary summary) {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(1, discovered.size());

        CrawlItem item = discovered.get(0);
        assertNotNull(item.getSourceDescriptor());
        assertEquals(SourceType.FILE, item.getSourceDescriptor().getType());
        assertTrue(item.getUrl().endsWith("test.txt"));
    }
}
