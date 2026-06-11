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

package ai.kompile.loader.gdocs;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state tracker for Google Docs crawling.
 * Tracks visited document IDs and the last sync timestamp for incremental crawls.
 */
public class GoogleDocsCrawlJob extends AbstractCrawlJob {

    private final Set<String> visitedDocIds = ConcurrentHashMap.newKeySet();
    private volatile String lastSyncEpoch;
    private volatile String lastPageToken;

    public GoogleDocsCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);
        restoreState(config);
    }

    private void restoreState(CrawlConfig config) {
        CrawlState previous = config.getPreviousState();
        if (previous == null) return;

        if (previous.getVisitedUrls() != null) {
            visitedDocIds.addAll(previous.getVisitedUrls());
        }

        Map<String, Object> props = previous.getProperties();
        if (props != null) {
            Object epoch = props.get("lastSyncEpoch");
            if (epoch != null) lastSyncEpoch = epoch.toString();
            Object token = props.get("lastPageToken");
            if (token != null) lastPageToken = token.toString();
        }
    }

    public boolean isVisited(String documentId) {
        return visitedDocIds.contains(documentId);
    }

    public void markVisited(String documentId) {
        visitedDocIds.add(documentId);
    }

    public String getLastSyncEpoch() {
        return lastSyncEpoch;
    }

    public void setLastSyncEpoch(String epoch) {
        this.lastSyncEpoch = epoch;
    }

    public String getLastPageToken() {
        return lastPageToken;
    }

    public void setLastPageToken(String pageToken) {
        this.lastPageToken = pageToken;
    }

    @Override
    public CrawlState checkpoint() {
        Map<String, Object> props = new HashMap<>();
        if (lastSyncEpoch != null) {
            props.put("lastSyncEpoch", lastSyncEpoch);
        }
        if (lastPageToken != null) {
            props.put("lastPageToken", lastPageToken);
        }

        return CrawlState.builder()
                .visitedUrls(Set.copyOf(visitedDocIds))
                .properties(props)
                .build();
    }
}
