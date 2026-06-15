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

package ai.kompile.core.crawler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent state of a crawl job, used for:
 * <ul>
 *   <li>Resuming an interrupted crawl</li>
 *   <li>Incremental re-crawls (only process new/changed items)</li>
 *   <li>Checkpointing progress during long-running crawls</li>
 * </ul>
 *
 * Crawlers serialize this to JSON and store it alongside crawl results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlState {

    /** When this state was captured */
    private Instant timestamp;

    /** URLs/paths already visited — used to skip on incremental re-crawl */
    @Builder.Default
    private Set<String> visitedUrls = new HashSet<>();

    /**
     * Content hashes of previously crawled items (URL -> hash).
     * On incremental re-crawl, items whose hash hasn't changed are skipped.
     */
    @Builder.Default
    private Map<String, String> contentHashes = new HashMap<>();

    /**
     * Last-modified timestamps of previously crawled items (URL -> epoch millis).
     * For filesystem crawls, this tracks file modification times.
     * For web crawls, this tracks Last-Modified headers.
     */
    @Builder.Default
    private Map<String, Long> lastModifiedTimes = new HashMap<>();

    /**
     * URLs/paths discovered but not yet visited at the time of checkpoint.
     * Stored as "url::depth" strings to preserve BFS depth information.
     * On resume, these are added back to the crawl frontier.
     */
    @Builder.Default
    private List<String> pendingUrls = new ArrayList<>();

    /**
     * Crawler-specific state that doesn't fit the standard fields.
     * For example, a SharePoint crawler might store a delta token here.
     */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    /**
     * Checks whether a URL/path was visited in the previous crawl.
     */
    public boolean wasVisited(String url) {
        return visitedUrls.contains(url);
    }

    /**
     * Checks whether a URL/path has changed since the last crawl,
     * based on content hash comparison.
     *
     * @return true if the item is new or has changed
     */
    public boolean hasChanged(String url, String currentHash) {
        String previousHash = contentHashes.get(url);
        return previousHash == null || !previousHash.equals(currentHash);
    }

    /**
     * Checks whether a file has been modified since the last crawl,
     * based on last-modified timestamp comparison.
     *
     * @return true if the item is new or has been modified
     */
    public boolean isModifiedSince(String path, long currentModifiedTime) {
        Long previousTime = lastModifiedTimes.get(path);
        return previousTime == null || currentModifiedTime > previousTime;
    }
}
