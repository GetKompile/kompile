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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.crawler.*;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job that tracks state for email inbox crawling.
 * Maintains visited paths and last-modified times for incremental re-crawls.
 */
public class EmailInboxCrawlJob extends AbstractCrawlJob {

    final Set<String> visitedPaths = ConcurrentHashMap.newKeySet();
    final Map<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();

    public EmailInboxCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Restore state from previous crawl for incremental mode
        CrawlState previousState = config.getPreviousState();
        if (previousState != null) {
            if (previousState.getVisitedUrls() != null) {
                visitedPaths.addAll(previousState.getVisitedUrls());
            }
            if (previousState.getLastModifiedTimes() != null) {
                lastModifiedTimes.putAll(previousState.getLastModifiedTimes());
            }
        }
    }

    /**
     * Checks if a file path was already processed in a previous crawl
     * and hasn't been modified since.
     *
     * @return true if the file should be processed (new or modified)
     */
    public boolean shouldProcess(String path, long currentModifiedTime) {
        Long previousTime = lastModifiedTimes.get(path);
        if (previousTime == null) {
            return true; // never seen before
        }
        return currentModifiedTime > previousTime;
    }

    public void markVisited(String path, long modifiedTime) {
        visitedPaths.add(path);
        lastModifiedTimes.put(path, modifiedTime);
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Set.copyOf(visitedPaths))
                .lastModifiedTimes(new HashMap<>(lastModifiedTimes))
                .build();
    }
}
