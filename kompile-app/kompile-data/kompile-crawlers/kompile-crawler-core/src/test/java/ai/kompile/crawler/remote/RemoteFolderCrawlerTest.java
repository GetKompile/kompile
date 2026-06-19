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

package ai.kompile.crawler.remote;

import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RemoteFolderCrawlerTest {

    @Test
    void testSupportedSourceTypes() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        Set<SourceType> supported = crawler.getSupportedSourceTypes();
        assertTrue(supported.contains(SourceType.S3));
        assertTrue(supported.contains(SourceType.SFTP));
        assertTrue(supported.contains(SourceType.SMB));
        assertFalse(supported.contains(SourceType.FILE));
        assertFalse(supported.contains(SourceType.DIRECTORY));
    }

    @Test
    void testCrawlerMetadata() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        assertEquals("remote-folder", crawler.getId());
        assertEquals("Remote Folder Crawler", crawler.getName());
        assertNotNull(crawler.getDescription());
        assertTrue(crawler.getDescription().contains("S3"));
        assertTrue(crawler.getDescription().contains("SFTP"));
        assertTrue(crawler.getDescription().contains("SMB"));
    }

    @Test
    void testValidation_S3_missingAccessKey() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("my-bucket/prefix")
                .sourceType(SourceType.S3)
                .properties(Map.of("secretKey", "test-secret"))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("accessKey")));
    }

    @Test
    void testValidation_S3_missingSecretKey() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("my-bucket/prefix")
                .sourceType(SourceType.S3)
                .properties(Map.of("accessKey", "test-key"))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("secretKey")));
    }

    @Test
    void testValidation_S3_valid() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("my-bucket/prefix")
                .sourceType(SourceType.S3)
                .properties(Map.of(
                        "accessKey", "test-key",
                        "secretKey", "test-secret"
                ))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
    }

    @Test
    void testValidation_SFTP_missingHost() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("/remote/path")
                .sourceType(SourceType.SFTP)
                .properties(Map.of("username", "user"))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("host")));
    }

    @Test
    void testValidation_SFTP_valid() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("/remote/path")
                .sourceType(SourceType.SFTP)
                .properties(Map.of(
                        "host", "sftp.example.com",
                        "username", "user"
                ))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
    }

    @Test
    void testValidation_SMB_missingPassword() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("share/path")
                .sourceType(SourceType.SMB)
                .properties(Map.of(
                        "host", "fileserver",
                        "username", "admin"
                ))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("password")));
    }

    @Test
    void testValidation_SMB_valid() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("share/path")
                .sourceType(SourceType.SMB)
                .properties(Map.of(
                        "host", "fileserver",
                        "username", "admin",
                        "password", "secret"
                ))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
    }

    @Test
    void testValidation_unsupportedSourceType() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("/local/path")
                .sourceType(SourceType.FILE)
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unsupported")));
    }

    @Test
    void testValidation_nullSourceType() {
        RemoteFolderCrawler crawler = new RemoteFolderCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("something")
                .sourceType(null)
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("sourceType")));
    }

    @Test
    void testCreateClient_S3() {
        RemoteFolderClient client = RemoteFolderCrawler.createClient(SourceType.S3);
        assertInstanceOf(S3FolderClient.class, client);
        assertEquals(SourceType.S3, client.sourceType());
    }

    @Test
    void testCreateClient_SFTP() {
        RemoteFolderClient client = RemoteFolderCrawler.createClient(SourceType.SFTP);
        assertInstanceOf(SftpFolderClient.class, client);
        assertEquals(SourceType.SFTP, client.sourceType());
    }

    @Test
    void testCreateClient_SMB() {
        RemoteFolderClient client = RemoteFolderCrawler.createClient(SourceType.SMB);
        assertInstanceOf(SmbFolderClient.class, client);
        assertEquals(SourceType.SMB, client.sourceType());
    }

    @Test
    void testCreateClient_unsupported() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteFolderCrawler.createClient(SourceType.FILE));
    }

    @Test
    void testRemoteFileEntry() {
        RemoteFileEntry entry = new RemoteFileEntry(
                "docs/reports/q4-report.pdf", "q4-report.pdf",
                1024, 1700000000000L, "application/pdf", "abc123");

        assertEquals("docs/reports/q4-report.pdf", entry.key());
        assertEquals("q4-report.pdf", entry.fileName());
        assertEquals("q4-report.pdf", entry.effectiveFileName());
        assertEquals(1024, entry.sizeBytes());
        assertEquals("application/pdf", entry.contentType());
        assertEquals("abc123", entry.etag());
    }

    @Test
    void testRemoteFileEntry_effectiveFileName_fromKey() {
        RemoteFileEntry entry = new RemoteFileEntry(
                "docs/reports/q4-report.pdf", null,
                1024, 0L, null, null);
        assertEquals("q4-report.pdf", entry.effectiveFileName());

        RemoteFileEntry entry2 = new RemoteFileEntry(
                "simple.txt", "",
                100, 0L, null, null);
        assertEquals("simple.txt", entry2.effectiveFileName());
    }

    @Test
    void testRemoteFolderCrawlJob_checkpoint() {
        CrawlConfig config = CrawlConfig.builder()
                .seed("bucket/prefix")
                .sourceType(SourceType.S3)
                .build();
        RemoteFolderCrawlJob job = new RemoteFolderCrawlJob("test-1", config,
                new CrawlEventListener() {});

        job.visitedKeys.add("docs/file1.pdf");
        job.visitedKeys.add("docs/file2.pdf");
        job.lastModifiedTimes.put("docs/file1.pdf", 1700000000000L);
        job.lastModifiedTimes.put("docs/file2.pdf", 1700000001000L);

        CrawlState checkpoint = job.checkpoint();
        assertNotNull(checkpoint);
        assertNotNull(checkpoint.getTimestamp());
        assertEquals(2, checkpoint.getVisitedUrls().size());
        assertTrue(checkpoint.getVisitedUrls().contains("docs/file1.pdf"));
        assertEquals(2, checkpoint.getLastModifiedTimes().size());
        assertEquals(1700000000000L, checkpoint.getLastModifiedTimes().get("docs/file1.pdf"));
    }

    @Test
    void testRemoteFolderCrawlJob_incrementalInit() {
        Map<String, Long> prevModTimes = new HashMap<>();
        prevModTimes.put("docs/old.pdf", 1600000000000L);
        CrawlState prevState = CrawlState.builder()
                .visitedUrls(Set.of("docs/old.pdf"))
                .lastModifiedTimes(prevModTimes)
                .build();

        CrawlConfig config = CrawlConfig.builder()
                .seed("bucket/prefix")
                .sourceType(SourceType.S3)
                .previousState(prevState)
                .build();
        RemoteFolderCrawlJob job = new RemoteFolderCrawlJob("test-2", config,
                new CrawlEventListener() {});

        // Should have inherited the previous mod times
        assertEquals(1, job.lastModifiedTimes.size());
        assertEquals(1600000000000L, job.lastModifiedTimes.get("docs/old.pdf"));
    }
}
