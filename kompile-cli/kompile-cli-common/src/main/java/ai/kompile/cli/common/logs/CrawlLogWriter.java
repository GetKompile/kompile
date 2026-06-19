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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Appends crawl-pipeline events as JSON-lines to {@code ~/.kompile/logs/crawls/<jobId>.log}, mirroring
 * the agent / subprocess log aggregation so crawl logs are CLI-accessible and retention-managed in one
 * place (see {@link LogRetentionManager#applyToCrawls()}).
 *
 * <p>The H2 job-log store remains the canonical record; this is a best-effort mirror — write failures
 * are swallowed so a full disk or permission issue can never break a crawl.
 */
public final class CrawlLogWriter {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private CrawlLogWriter() {
    }

    /** Append one crawl event to the job's central log file. No-op on null/blank jobId or record. */
    public static void append(String jobId, CrawlLogRecord record) {
        if (jobId == null || jobId.isBlank() || record == null) {
            return;
        }
        try {
            LogPaths.ensureCrawlsDir();
            Files.writeString(
                    LogPaths.crawlLogFile(jobId).toPath(),
                    MAPPER.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best-effort mirror; the H2 job-log store is the source of truth.
        }
    }

    /** One crawl event line. */
    public record CrawlLogRecord(Instant timestamp, String phase, String level, String message,
                                 String details, long seq) {
    }
}
