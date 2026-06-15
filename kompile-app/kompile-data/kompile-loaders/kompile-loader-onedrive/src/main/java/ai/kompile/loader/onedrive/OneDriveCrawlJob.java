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

package ai.kompile.loader.onedrive;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for the OneDrive crawler.
 *
 * <p>Tracks visited item IDs and last-modified timestamps for incremental crawls.
 * Also holds the temporary directory used for downloaded files so that the caller
 * can clean it up after processing.</p>
 */
public class OneDriveCrawlJob extends AbstractCrawlJob {

    /** OneDrive item IDs already processed during this run */
    final Set<String> visitedItemIds = ConcurrentHashMap.newKeySet();

    /**
     * OneDrive item ID -> last-modified timestamp (epoch millis) for incremental tracking.
     * Seeded from {@link CrawlConfig#getPreviousState()} at construction time.
     */
    final Map<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    /** Local temp directory where downloaded file copies are stored */
    volatile Path downloadDir;

    OneDriveCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Seed incremental state from the previous crawl run (if any)
        CrawlState prev = config.getPreviousState();
        if (prev != null && prev.getLastModifiedTimes() != null) {
            lastModifiedTimes.putAll(prev.getLastModifiedTimes());
        }
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedItemIds)))
                .lastModifiedTimes(Collections.unmodifiableMap(new HashMap<>(lastModifiedTimes)))
                .build();
    }
}
