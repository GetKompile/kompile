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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import ai.kompile.core.crawler.*;
import ai.kompile.crawler.AbstractCrawlJob;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for Gmail crawling.
 * Tracks visited message IDs and the last history ID for incremental sync.
 */
public class GmailCrawlJob extends AbstractCrawlJob {

    /** Message IDs already processed in this or a previous crawl. */
    private final Set<String> visitedMessageIds = ConcurrentHashMap.newKeySet();

    /** Gmail history ID from the last sync — used for incremental crawls via History API. */
    private volatile String lastHistoryId;

    /** Epoch seconds of the last successful sync — fallback for date-based incremental. */
    private volatile String lastSyncEpoch;

    public GmailCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Restore previous state if resuming an incremental crawl
        CrawlState previousState = config.getPreviousState();
        if (previousState != null) {
            if (previousState.getVisitedUrls() != null) {
                visitedMessageIds.addAll(previousState.getVisitedUrls());
            }
            Map<String, Object> props = previousState.getProperties();
            if (props != null) {
                lastHistoryId = props.get("lastHistoryId") != null
                        ? props.get("lastHistoryId").toString() : null;
                lastSyncEpoch = props.get("lastSyncEpoch") != null
                        ? props.get("lastSyncEpoch").toString() : null;
            }
        }
    }

    /**
     * Returns true if this message has already been visited.
     */
    public boolean isVisited(String messageId) {
        return visitedMessageIds.contains(messageId);
    }

    /**
     * Marks a message as visited.
     */
    public void markVisited(String messageId) {
        visitedMessageIds.add(messageId);
    }

    public String getLastHistoryId() {
        return lastHistoryId;
    }

    public void setLastHistoryId(String historyId) {
        this.lastHistoryId = historyId;
    }

    public String getLastSyncEpoch() {
        return lastSyncEpoch;
    }

    public void setLastSyncEpoch(String epoch) {
        this.lastSyncEpoch = epoch;
    }

    @Override
    public CrawlState checkpoint() {
        Map<String, Object> props = new HashMap<>();
        if (lastHistoryId != null) {
            props.put("lastHistoryId", lastHistoryId);
        }
        if (lastSyncEpoch != null) {
            props.put("lastSyncEpoch", lastSyncEpoch);
        }

        return CrawlState.builder()
                .visitedUrls(new HashSet<>(visitedMessageIds))
                .properties(props)
                .build();
    }
}
