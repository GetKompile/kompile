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

package ai.kompile.loader.slack;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks crawl state for Slack workspace crawling.
 * <p>
 * State tracking:
 * - visitedChannels: set of channel IDs already fully crawled
 * - latestTimestamps: maps channelId -> latest (newest) message ts processed, for incremental crawls
 */
public class SlackCrawlJob extends AbstractCrawlJob {

    final Set<String> visitedChannels = ConcurrentHashMap.newKeySet();
    final Map<String, String> latestTimestamps = new ConcurrentHashMap<>();

    SlackCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Restore state from previous crawl if available
        CrawlState previousState = config.getPreviousState();
        if (previousState != null) {
            if (previousState.getVisitedUrls() != null) {
                visitedChannels.addAll(previousState.getVisitedUrls());
            }
            if (previousState.getProperties() != null) {
                Object lts = previousState.getProperties().get("latestTimestamps");
                if (lts instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> latestTimestamps.put(k.toString(), v.toString()));
                }
            }
        }
    }

    /**
     * Get the incremental "oldest" timestamp for a channel.
     * Returns the latest message timestamp from the previous crawl, or null if no prior state.
     */
    String getIncrementalOldestForChannel(String channelId) {
        return latestTimestamps.get(channelId);
    }

    /**
     * Record the newest message timestamp seen in a channel for incremental re-crawl.
     */
    void recordNewestTimestamp(String channelId, String ts) {
        latestTimestamps.merge(channelId, ts, (existing, incoming) -> {
            try {
                double ex = Double.parseDouble(existing);
                double in = Double.parseDouble(incoming);
                return ex >= in ? existing : incoming;
            } catch (NumberFormatException e) {
                return incoming;
            }
        });
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Set.copyOf(visitedChannels))
                .properties(Map.of("latestTimestamps", Map.copyOf(latestTimestamps)))
                .build();
    }
}
