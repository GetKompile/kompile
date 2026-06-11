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

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State holder for an in-progress Excel crawl job.
 *
 * <p>Tracks visited file paths and their last-modified timestamps for
 * incremental re-crawl support. When the job checkpoints, this state is
 * serialized and can be restored on the next crawl of the same seed directory.
 */
public class ExcelCrawlJob extends AbstractCrawlJob {

    /** Absolute paths already processed in this crawl run. */
    final Set<String> visitedPaths = ConcurrentHashMap.newKeySet();

    /** Path → last-modified millis — persisted across crawls for incrementality. */
    final ConcurrentHashMap<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    ExcelCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);
        // Warm from previous crawl state if available
        CrawlState prev = config.getPreviousState();
        if (prev != null && prev.getLastModifiedTimes() != null) {
            lastModifiedTimes.putAll(prev.getLastModifiedTimes());
        }
    }

    /**
     * Returns {@code true} if the file at {@code absolutePath} was already
     * processed in a previous crawl and has not been modified since.
     */
    boolean isAlreadyProcessed(String absolutePath, Path file) {
        if (visitedPaths.contains(absolutePath)) return true;
        Long previousModTime = lastModifiedTimes.get(absolutePath);
        if (previousModTime == null) return false;
        try {
            long currentModTime = Files.getLastModifiedTime(file).toMillis();
            return currentModTime <= previousModTime;
        } catch (IOException e) {
            return false; // re-process on error
        }
    }

    /**
     * Records that the file has been processed and stores its last-modified time.
     */
    void markProcessed(String absolutePath, Path file) {
        visitedPaths.add(absolutePath);
        try {
            lastModifiedTimes.put(absolutePath, Files.getLastModifiedTime(file).toMillis());
        } catch (IOException ignored) {}
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(java.time.Instant.now())
                .visitedUrls(Collections.unmodifiableSet(visitedPaths))
                .lastModifiedTimes(Collections.unmodifiableMap(lastModifiedTimes))
                .build();
    }
}
