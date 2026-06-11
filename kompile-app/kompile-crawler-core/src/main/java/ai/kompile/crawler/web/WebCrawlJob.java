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

package ai.kompile.crawler.web;

import ai.kompile.core.crawler.*;
import ai.kompile.crawler.AbstractCrawlJob;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Crawl job state for the web crawler.
 * Tracks visited URLs and maintains the frontier queue state.
 */
public class WebCrawlJob extends AbstractCrawlJob {

    /** URLs already visited or enqueued — used for deduplication */
    final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();

    /** Content hashes for change detection */
    final Map<String, String> contentHashes = new ConcurrentHashMap<>();

    /**
     * BFS frontier shared between crawler thread and checkpoint snapshots.
     * Using ConcurrentLinkedDeque so checkpoint() can safely snapshot
     * the frontier while the crawler thread is running.
     */
    final Deque<Map.Entry<String, Integer>> pendingFrontier = new ConcurrentLinkedDeque<>();

    WebCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Initialize from previous state if doing incremental crawl
        CrawlState prev = config.getPreviousState();
        if (prev != null) {
            visitedUrls.addAll(prev.getVisitedUrls());
            contentHashes.putAll(prev.getContentHashes());

            // Restore pending frontier from checkpoint
            if (prev.getPendingUrls() != null) {
                for (String encoded : prev.getPendingUrls()) {
                    String[] parts = encoded.split("::", 2);
                    if (parts.length == 2) {
                        try {
                            pendingFrontier.add(new AbstractMap.SimpleImmutableEntry<>(
                                    parts[0], Integer.parseInt(parts[1])));
                        } catch (NumberFormatException ignored) {
                            // Skip malformed entries
                        }
                    }
                }
            }
        }
    }

    @Override
    public CrawlState checkpoint() {
        // Snapshot the pending frontier as "url::depth" strings
        List<String> pending = pendingFrontier.stream()
                .map(e -> e.getKey() + "::" + e.getValue())
                .collect(Collectors.toList());

        return CrawlState.builder()
                .timestamp(java.time.Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedUrls)))
                .contentHashes(Collections.unmodifiableMap(new HashMap<>(contentHashes)))
                .pendingUrls(pending)
                .build();
    }
}
