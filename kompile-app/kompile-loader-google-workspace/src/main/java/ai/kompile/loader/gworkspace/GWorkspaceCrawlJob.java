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

package ai.kompile.loader.gworkspace;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks crawl state for Google Workspace crawling.
 * <p>
 * State tracking:
 * - visitedIds: set of Gmail message IDs, Drive file IDs, or Calendar event IDs already processed
 * - lastSyncTimes: maps service name (gmail, drive, calendar) to the latest item timestamp for incremental crawls
 */
public class GWorkspaceCrawlJob extends AbstractCrawlJob {

    final Set<String> visitedIds = ConcurrentHashMap.newKeySet();
    final Map<String, String> lastSyncTimes = new ConcurrentHashMap<>();

    GWorkspaceCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        CrawlState previousState = config.getPreviousState();
        if (previousState != null) {
            if (previousState.getVisitedUrls() != null) {
                visitedIds.addAll(previousState.getVisitedUrls());
            }
            if (previousState.getProperties() != null) {
                Object lst = previousState.getProperties().get("lastSyncTimes");
                if (lst instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> lastSyncTimes.put(k.toString(), v.toString()));
                }
            }
        }
    }

    /**
     * Get the last sync time for a service for incremental crawl.
     */
    String getLastSyncTime(String service) {
        return lastSyncTimes.get(service);
    }

    /**
     * Record the latest timestamp for incremental re-crawl.
     */
    void recordSyncTime(String service, String timestamp) {
        lastSyncTimes.put(service, timestamp);
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Set.copyOf(visitedIds))
                .properties(Map.of("lastSyncTimes", Map.copyOf(lastSyncTimes)))
                .build();
    }
}
