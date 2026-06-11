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

package ai.kompile.loader.discord;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks crawl state for Discord server crawling.
 * <p>
 * State tracking:
 * - visitedChannels: set of channel IDs already fully crawled
 * - lastMessageIds: maps channelId -> last (newest) message ID processed, for incremental crawls
 */
public class DiscordCrawlJob extends AbstractCrawlJob {

    final Set<String> visitedChannels = ConcurrentHashMap.newKeySet();
    final Map<String, String> lastMessageIds = new ConcurrentHashMap<>();

    DiscordCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Restore state from previous crawl if available
        CrawlState previousState = config.getPreviousState();
        if (previousState != null) {
            if (previousState.getVisitedUrls() != null) {
                visitedChannels.addAll(previousState.getVisitedUrls());
            }
            if (previousState.getProperties() != null) {
                Object lmi = previousState.getProperties().get("lastMessageIds");
                if (lmi instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> lastMessageIds.put(k.toString(), v.toString()));
                }
            }
        }
    }

    /**
     * Check if a channel should be processed in an incremental crawl.
     * Returns the "after" snowflake ID for this channel, or null if no prior state.
     */
    String getIncrementalAfterForChannel(String channelId) {
        return lastMessageIds.get(channelId);
    }

    /**
     * Record the newest message ID seen in a channel for incremental re-crawl.
     */
    void recordNewestMessage(String channelId, String messageId) {
        lastMessageIds.merge(channelId, messageId, (existing, incoming) -> {
            // Keep the higher (newer) snowflake
            try {
                long ex = Long.parseUnsignedLong(existing);
                long in = Long.parseUnsignedLong(incoming);
                return Long.compareUnsigned(ex, in) >= 0 ? existing : incoming;
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
                .properties(Map.of("lastMessageIds", Map.copyOf(lastMessageIds)))
                .build();
    }
}
