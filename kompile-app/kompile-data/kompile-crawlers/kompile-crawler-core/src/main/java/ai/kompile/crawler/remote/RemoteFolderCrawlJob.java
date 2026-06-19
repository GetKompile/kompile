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
import ai.kompile.crawler.AbstractCrawlJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for the remote folder crawler.
 * Tracks downloaded files and remote ETags/checksums for incremental crawls.
 */
public class RemoteFolderCrawlJob extends AbstractCrawlJob {

    /** Remote keys/paths already processed */
    final Set<String> visitedKeys = ConcurrentHashMap.newKeySet();

    /** Remote key -> last-modified timestamp for incremental tracking */
    final Map<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    /** Local temp directory where remote files are downloaded */
    volatile Path downloadDir;

    RemoteFolderCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Initialize from previous state for incremental crawling
        CrawlState prev = config.getPreviousState();
        if (prev != null && prev.getLastModifiedTimes() != null) {
            lastModifiedTimes.putAll(prev.getLastModifiedTimes());
        }
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedKeys)))
                .lastModifiedTimes(Collections.unmodifiableMap(new HashMap<>(lastModifiedTimes)))
                .build();
    }
}
