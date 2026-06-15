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
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for the HTML file crawler.
 * Tracks visited paths, content hashes for incremental crawling,
 * and discovered links between local HTML files.
 */
public class HtmlFileCrawlJob extends AbstractCrawlJob {

    /** Paths already processed */
    final Set<String> visitedPaths = ConcurrentHashMap.newKeySet();

    /** Content hashes for incremental crawling */
    final Map<String, String> contentHashes = new ConcurrentHashMap<>();

    /** File modification times for incremental tracking */
    final Map<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    HtmlFileCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        CrawlState prev = config.getPreviousState();
        if (prev != null) {
            if (prev.getContentHashes() != null) {
                contentHashes.putAll(prev.getContentHashes());
            }
            if (prev.getLastModifiedTimes() != null) {
                lastModifiedTimes.putAll(prev.getLastModifiedTimes());
            }
        }
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedPaths)))
                .contentHashes(Collections.unmodifiableMap(new HashMap<>(contentHashes)))
                .lastModifiedTimes(Collections.unmodifiableMap(new HashMap<>(lastModifiedTimes)))
                .build();
    }
}
