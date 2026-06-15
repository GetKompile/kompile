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

package ai.kompile.loader.gdrive;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for the Google Drive crawler.
 *
 * <p>Tracks visited Drive file IDs and their modification times to support
 * incremental crawls. On a second pass the crawler can skip files that
 * have not changed since the last run by comparing Drive's {@code modifiedTime}
 * field against the timestamps stored here.</p>
 *
 * <p>Also carries the resolved OAuth access token so that the execution logic
 * in {@link GoogleDriveCrawler} can refresh it mid-crawl (on HTTP 401) and
 * propagate the new token across recursive calls without passing it through
 * every method signature.</p>
 */
public class GoogleDriveCrawlJob extends AbstractCrawlJob {

    /**
     * Drive file/folder IDs already emitted as {@link ai.kompile.core.crawler.CrawlItem}s.
     * Used to prevent duplicate emission when a file appears in multiple parent folders
     * (Drive supports multi-parent files).
     */
    final Set<String> visitedFileIds = ConcurrentHashMap.newKeySet();

    /**
     * Maps Drive file ID → {@code modifiedTime} epoch-millis from the last crawl.
     * Populated from {@link CrawlState#getLastModifiedTimes()} on start, updated as
     * new files are discovered.
     */
    final Map<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    /**
     * The OAuth access token in use for this job.
     * Mutable so that {@link GoogleDriveCrawler} can atomically replace it after a
     * successful token refresh without restarting the job.
     */
    volatile String accessToken;

    GoogleDriveCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Restore incremental state from a previous run if available.
        CrawlState prev = config.getPreviousState();
        if (prev != null) {
            if (prev.getVisitedUrls() != null) {
                visitedFileIds.addAll(prev.getVisitedUrls());
            }
            if (prev.getLastModifiedTimes() != null) {
                lastModifiedTimes.putAll(prev.getLastModifiedTimes());
            }
        }
    }

    /**
     * Records a file ID as visited so it will not be emitted again in the same run,
     * and stores its {@code modifiedTime} for future incremental comparison.
     *
     * @param fileId       Drive file ID
     * @param modifiedTimeMillis epoch-millis from the Drive {@code modifiedTime} field,
     *                     or {@code 0} if unavailable
     */
    void markVisited(String fileId, long modifiedTimeMillis) {
        visitedFileIds.add(fileId);
        if (modifiedTimeMillis > 0) {
            lastModifiedTimes.put(fileId, modifiedTimeMillis);
        }
    }

    /**
     * Returns {@code true} if this file has already been emitted in the current run.
     */
    boolean wasVisited(String fileId) {
        return visitedFileIds.contains(fileId);
    }

    /**
     * Returns {@code true} if the file has not changed since the last crawl.
     * Always returns {@code false} (i.e., "changed") when there is no previous state.
     *
     * @param fileId             Drive file ID
     * @param modifiedTimeMillis current epoch-millis from Drive API response
     */
    boolean isUnchanged(String fileId, long modifiedTimeMillis) {
        Long previous = lastModifiedTimes.get(fileId);
        return previous != null && modifiedTimeMillis <= previous;
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedFileIds)))
                .lastModifiedTimes(Collections.unmodifiableMap(new HashMap<>(lastModifiedTimes)))
                .build();
    }
}
