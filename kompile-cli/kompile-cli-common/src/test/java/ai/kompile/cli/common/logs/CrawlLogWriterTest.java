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

package ai.kompile.cli.common.logs;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlLogWriterTest {

    @Test
    void appendsJsonLinesUnderKompileLogsCrawls(@TempDir File home) throws Exception {
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", home.getAbsolutePath());
        try {
            CrawlLogWriter.append("job-1", new CrawlLogWriter.CrawlLogRecord(
                    Instant.now(), "GRAPH_EXTRACTION", "INFO", "extracted 5 entities", "ok", 1));
            CrawlLogWriter.append("job-1", new CrawlLogWriter.CrawlLogRecord(
                    Instant.now(), "VECTOR_INDEXING", "WARN", "deferred", null, 2));

            File logFile = LogPaths.crawlLogFile("job-1");
            assertTrue(logFile.isFile(), "crawl log file created under ~/.kompile/logs/crawls");
            List<String> lines = Files.readAllLines(logFile.toPath());
            assertEquals(2, lines.size(), "one JSON line per appended event");
            JsonNode first = JsonUtils.standardMapper().readTree(lines.get(0));
            assertEquals("GRAPH_EXTRACTION", first.get("phase").asText());
            assertEquals("INFO", first.get("level").asText());
            assertEquals(1L, first.get("seq").asLong());
        } finally {
            if (prevHome != null) {
                System.setProperty("user.home", prevHome);
            }
        }
    }

    @Test
    void nullJobIdIsNoOp(@TempDir File home) {
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", home.getAbsolutePath());
        try {
            CrawlLogWriter.append(null, new CrawlLogWriter.CrawlLogRecord(Instant.now(), "X", "INFO", "m", null, 1));
            assertFalse(new File(home, ".kompile/logs/crawls").exists(), "no file written for a null jobId");
        } finally {
            if (prevHome != null) {
                System.setProperty("user.home", prevHome);
            }
        }
    }
}
