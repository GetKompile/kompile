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

/**
 * Callback interface for receiving events during a crawl.
 * All methods have default no-op implementations so listeners can
 * subscribe to only the events they care about.
 */
public interface CrawlEventListener {

    /**
     * Called when a new document/page/file is discovered by the crawler.
     * This fires before the item is loaded or indexed.
     */
    default void onDocumentDiscovered(CrawlItem item) {}

    /**
     * Called when a discovered item has been successfully loaded and indexed.
     */
    default void onDocumentProcessed(CrawlItem item) {}

    /**
     * Called when processing of a discovered item fails.
     */
    default void onDocumentFailed(CrawlItem item, Exception error) {}

    /**
     * Called when a URL/path is skipped (filtered out, already visited, unchanged).
     */
    default void onDocumentSkipped(String url, String reason) {}

    /**
     * Called periodically with a progress snapshot.
     */
    default void onProgress(CrawlProgress progress) {}

    /**
     * Called when the crawl completes (successfully, by cancellation, or by failure).
     */
    default void onComplete(CrawlSummary summary) {}
}
